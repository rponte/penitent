package io.github.rponte.penitent.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * What describing routines costs, on one connection (#169): each dictionary query, with its rows, and the session's
 * round trips to the database. The round trips come from {@code v$sesstat}, read for the measured session's SID on an
 * administrator's connection, so the test user needs no privilege it does not have; a database where that cannot be
 * read, as the 19c image, gives -1.
 *
 * <p>The DataSource it gives hands out the one measured connection, which a close does not close, so that every
 * statement of a describe is on the measured session.
 */
final class DictionaryProfiler implements AutoCloseable {

	private static final Pattern VIEW = Pattern.compile("(?i)\\bfrom (all_\\w+|dual)|\\b(dbms_utility)\\b");

	/** One dictionary query: its SQL, as one line, and the rows it returned. */
	record Query(String sql, int rows) {
	}

	/** What one measured action cost. */
	record Cost(List<Query> queries, long roundTrips, long nanos) {

		int count() {
			return queries.size();
		}

		/** The queries by the view they read, such as {@code all_arguments}, with how many of each. */
		Map<String, Integer> byView() {
			Map<String, Integer> views = new LinkedHashMap<>();
			for (Query query : queries) {
				views.merge(viewOf(query.sql()), 1, Integer::sum);
			}
			return views;
		}

		int rows() {
			return queries.stream().mapToInt(Query::rows).sum();
		}

		@Override
		public String toString() {
			return String.format(Locale.ROOT, "%d queries, %d rows, %d round trips, %.1f ms %s", count(), rows(),
					roundTrips, nanos / 1e6, byView());
		}
	}

	private final Connection connection;
	private final Connection admin;
	private final long sid;
	private final DataSource dataSource;
	private final List<Query> queries = Collections.synchronizedList(new ArrayList<>());

	/**
	 * @param target where the measured connection comes from
	 * @param admin  an administrator's connection, to read the measured session's statistics, or {@code null}
	 */
	DictionaryProfiler(DataSource target, Connection admin) throws SQLException {
		this.connection = target.getConnection();
		this.admin = admin;
		try (PreparedStatement statement = connection.prepareStatement("select sys_context('userenv', 'sid') from dual");
				ResultSet rs = statement.executeQuery()) {
			rs.next();
			this.sid = rs.getLong(1);
		}
		Connection measured = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { Connection.class }, (p, m, a) -> {
					if (m.getName().equals("close")) {
						return null;
					}
					Object result = invoke(connection, m, a);
					if (m.getName().equals("prepareStatement") && result instanceof PreparedStatement statement
							&& isDictionary((String) a[0])) {
						return timed(statement, (String) a[0]);
					}
					if (m.getName().equals("prepareCall") && result instanceof java.sql.CallableStatement call
							&& isDictionary((String) a[0])) {
						return counted(call, (String) a[0]);
					}
					return result;
				});
		this.dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { DataSource.class }, (p, m, a) -> {
					if (m.getName().equals("getConnection")) {
						return measured;
					}
					throw new UnsupportedOperationException(m.getName());
				});
	}

	DataSource dataSource() {
		return dataSource;
	}

	/** Runs {@code action} on the measured connection, and says what it cost. */
	Cost measure(Supplier<?> action) {
		queries.clear();
		long before = roundTrips();
		long start = System.nanoTime();
		action.get();
		long nanos = System.nanoTime() - start;
		long after = roundTrips();
		return new Cost(List.copyOf(queries), before < 0 || after < 0 ? -1 : after - before, nanos);
	}

	/** The measured session's round trips so far, or -1 when its statistics cannot be read. */
	long roundTrips() {
		if (admin == null) {
			return -1;
		}
		try (PreparedStatement statement = admin.prepareStatement("""
				select s.value from v$sesstat s join v$statname n on n.statistic# = s.statistic#
				 where s.sid = ? and n.name = 'SQL*Net roundtrips to/from client'""")) {
			statement.setLong(1, sid);
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next() ? rs.getLong(1) : -1;
			}
		} catch (SQLException e) {
			return -1;
		}
	}

	@Override
	public void close() throws SQLException {
		connection.close();
		if (admin != null) {
			admin.close();
		}
	}

	private PreparedStatement timed(PreparedStatement real, String sql) {
		return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { PreparedStatement.class }, (p, m, a) -> {
					if (!m.getName().equals("executeQuery")) {
						return invoke(real, m, a);
					}
					ResultSet rs = (ResultSet) invoke(real, m, a);
					int[] rows = { 0 };
					return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { ResultSet.class },
							(rp, rm, ra) -> {
								Object result = invoke(rs, rm, ra);
								if (rm.getName().equals("next") && Boolean.TRUE.equals(result)) {
									rows[0]++;
								}
								if (rm.getName().equals("close")) {
									queries.add(new Query(sql.replaceAll("\\s+", " ").trim(), rows[0]));
								}
								return result;
							});
				});
	}

	/** A dictionary call, as DBMS_UTILITY.NAME_RESOLVE's block: counted as a query when it runs. */
	private java.sql.CallableStatement counted(java.sql.CallableStatement real, String sql) {
		return (java.sql.CallableStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { java.sql.CallableStatement.class }, (p, m, a) -> {
					Object result = invoke(real, m, a);
					if (m.getName().equals("execute")) {
						queries.add(new Query(sql.replaceAll("\\s+", " ").trim(), 0));
					}
					return result;
				});
	}

	private static boolean isDictionary(String sql) {
		return sql.matches("(?is).*\\b(all_\\w+|sys_context|dbms_utility)\\b.*");
	}

	static String viewOf(String sql) {
		Matcher view = VIEW.matcher(sql);
		return view.find() ? (view.group(1) != null ? view.group(1) : view.group(2)).toLowerCase(Locale.ROOT) : "other";
	}

	private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}
}
