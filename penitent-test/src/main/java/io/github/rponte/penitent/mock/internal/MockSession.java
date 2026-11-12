package io.github.rponte.penitent.mock.internal;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.proxy.Views;

/**
 * One test's mocks: its edition, the stubs of each package, compiled into the edition as they come, and the calls
 * recorded, for {@code verify}. Everything that is DDL runs on a connection of its own, outside any transaction of
 * the test, since DDL commits.
 */
public final class MockSession {

	/** The version of {@code penitent-mock-schema.sql} this library expects. */
	static final int SCHEMA_VERSION = 1;

	private static final String PREFIX = "PENITENT_MOCK_";

	/**
	 * The editions of the mocks that began in this JVM and have not ended. One found in the database and not here was
	 * left by a run that died, and is dropped; one found here belongs to mocks still on, and is left alone.
	 */
	private static final java.util.Set<String> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final EditionDataSource dataSource;
	private final DataSource base;
	private final Map<Class<?>, Object> describers = new HashMap<>();
	private final Map<String, String> bodies = new HashMap<>();
	private final Map<String, List<Stub>> stubsByPackage = new LinkedHashMap<>();
	private String edition;
	private String defaultEdition;

	public MockSession(EditionDataSource dataSource) {
		this.dataSource = dataSource;
		this.base = dataSource.target();
	}

	// ---------------------------------------------------------------- the edition

	public void begin() {
		if (edition != null) {
			throw new IllegalStateException("the mocks already began, in edition " + edition + ": end them first");
		}
		try (Connection connection = connection()) {
			Prerequisites.require(connection, SCHEMA_VERSION);
			defaultEdition = single(connection,
					"SELECT PROPERTY_VALUE FROM DATABASE_PROPERTIES WHERE PROPERTY_NAME = 'DEFAULT_EDITION'");
			dropLeftovers(connection);
			String created = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
			EditionDataSource.execute(connection, "CREATE EDITION " + created + " AS CHILD OF " + defaultEdition);
			ACTIVE.add(created);
			edition = created;
		} catch (SQLException e) {
			throw new IllegalStateException("failed to create the test's edition: " + e.getMessage(), e);
		}
		dataSource.enter(edition, defaultEdition);
	}

	public void end() {
		if (edition == null) {
			return;
		}
		String ending = edition;
		dataSource.enter(null, defaultEdition);
		edition = null;
		stubsByPackage.clear();
		bodies.clear();
		// ended either way: an edition that cannot be dropped now is a leftover, for the next begin to drop
		ACTIVE.remove(ending);
		try (Connection connection = connection()) {
			drop(connection, ending);
		} catch (SQLException e) {
			throw new IllegalStateException("failed to drop the test's edition " + ending + " (a connection taken in the"
					+ " test may still be open, and in it): " + e.getMessage(), e);
		}
	}

	/**
	 * Editions left by a run that died: there can be only one child of the default edition. One of mocks still on in
	 * this JVM is not a leftover: these mocks are refused instead, since only one test mocks a database at a time.
	 */
	private static void dropLeftovers(Connection connection) throws SQLException {
		List<String> found = strings(connection,
				"SELECT EDITION_NAME FROM ALL_EDITIONS WHERE EDITION_NAME LIKE 'PENITENT\\_MOCK\\_%' ESCAPE '\\'");
		for (String edition : found) {
			if (ACTIVE.contains(edition)) {
				throw new IllegalStateException("other mocks began on this database, in edition " + edition + ", and have"
						+ " not ended: tests that mock a database run one at a time, since an edition can have only one"
						+ " child, and two pools of the same database cannot share mocks yet");
			}
		}
		for (String leftover : found) {
			drop(connection, leftover);
		}
	}

	private static void drop(Connection connection, String edition) throws SQLException {
		EditionDataSource.execute(connection, "DROP EDITION " + edition + " CASCADE");
		try (PreparedStatement delete = connection.prepareStatement(
				"DELETE FROM PENITENT_MOCK_CALLS WHERE EDITION_NAME = ?")) {
			delete.setString(1, edition);
			delete.executeUpdate();
		}
		if (!connection.getAutoCommit()) {
			connection.commit();
		}
	}

	// ---------------------------------------------------------------- stubbing

	@SuppressWarnings("unchecked")
	public <T> T mock(Class<T> type) {
		requireBegun();
		describerOf(type);
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, new MockHandler(this, type));
	}

	/** Adds {@code stub}, or replaces it when it is the same object, and compiles its package again. */
	public void stub(Stub stub, Stub replaced) {
		requireBegun();
		RoutineMetadata routine = routineOf(stub.invocation());
		String pkg = packageOf(routine);
		List<Stub> stubs = stubsByPackage.computeIfAbsent(pkg, k -> new ArrayList<>());
		int index = replaced == null ? -1 : stubs.indexOf(replaced);
		if (index >= 0) {
			stubs.set(index, stub);
		} else {
			stubs.add(stub);
		}
		try {
			compile(pkg, routine);
		} catch (RuntimeException e) {
			stubs.remove(stub);
			throw e;
		}
	}

	private void compile(String pkg, RoutineMetadata any) {
		String body = bodies.computeIfAbsent(pkg, k -> source(any));
		Map<String, BodyRewriter.Subprogram> subprograms = new LinkedHashMap<>();
		for (Stub stub : stubsByPackage.get(pkg)) {
			RoutineMetadata routine = routineOf(stub.invocation());
			subprograms.computeIfAbsent(BodyRewriter.callKey(routine),
					k -> new BodyRewriter.Subprogram(routine, new ArrayList<>()))
					.stubs().add(new BodyRewriter.StubbedCall(byParameter(routine, stub.invocation()), stub.action()));
		}
		String mocked = BodyRewriter.rewrite(pkg, body, List.copyOf(subprograms.values()));
		try (Connection connection = connection()) {
			try {
				EditionDataSource.execute(connection, "ALTER SESSION SET EDITION = " + edition);
				EditionDataSource.execute(connection, mocked);
				List<String> errors = strings(connection, "SELECT LINE || ': ' || TEXT FROM ALL_ERRORS WHERE OWNER = '"
						+ any.schema() + "' AND NAME = '" + any.packageName() + "' AND TYPE = 'PACKAGE BODY'"
						+ " ORDER BY SEQUENCE");
				if (!errors.isEmpty()) {
					throw new IllegalStateException("the mocked body of " + pkg + " does not compile:\n  "
							+ String.join("\n  ", errors) + "\n" + mocked);
				}
			} finally {
				EditionDataSource.execute(connection, "ALTER SESSION SET EDITION = " + defaultEdition);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to compile the mocked body of " + pkg + ": " + e.getMessage(), e);
		}
	}

	/** The body as the database has it, in the default edition. */
	private String source(RoutineMetadata routine) {
		String pkg = packageOf(routine);
		try (Connection connection = connection();
				PreparedStatement select = connection.prepareStatement("SELECT TEXT FROM ALL_SOURCE WHERE OWNER = ?"
						+ " AND NAME = ? AND TYPE = 'PACKAGE BODY' ORDER BY LINE")) {
			select.setString(1, routine.schema());
			select.setString(2, routine.packageName());
			StringBuilder text = new StringBuilder();
			try (ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					text.append(rows.getString(1));
				}
			}
			if (text.isEmpty()) {
				throw new IllegalStateException(pkg + " has no package body to mock, or it cannot be read from ALL_SOURCE");
			}
			String firstLine = text.toString().lines().findFirst().orElse("").toUpperCase(Locale.ROOT);
			if (firstLine.matches(".*\\bWRAPPED\\s*$")) {
				throw new IllegalStateException(pkg + " has a wrapped body, which cannot be parsed, so it cannot be mocked");
			}
			return "CREATE OR REPLACE " + text;
		} catch (SQLException e) {
			throw new IllegalStateException("failed to read the body of " + pkg + ": " + e.getMessage(), e);
		}
	}

	// ---------------------------------------------------------------- verification

	public void verify(Invocation invocation, int times) {
		RoutineMetadata routine = routineOf(invocation);
		requireStubbed(routine);
		Map<String, Matcher> byParameter = byParameter(routine, invocation);
		StringBuilder sql = new StringBuilder(
				"SELECT COUNT(*) FROM PENITENT_MOCK_CALLS WHERE EDITION_NAME = ? AND ROUTINE = ?");
		int index = 0;
		for (RoutineMetadata.Argument parameter : routine.parameters()) {
			if (!parameter.isIn()) {
				continue;
			}
			Matcher matcher = byParameter.get(parameter.name());
			String check = matcher == null ? null
					: matcher.condition("JSON_VALUE(ARGS, '$[" + index + "]' RETURNING " + jsonType(parameter) + ")");
			if (check != null) {
				sql.append(" AND ").append(check);
			}
			index++;
		}
		int calls;
		try (Connection connection = connection();
				PreparedStatement count = connection.prepareStatement(sql.toString())) {
			count.setString(1, edition);
			count.setString(2, BodyRewriter.callKey(routine));
			try (ResultSet rows = count.executeQuery()) {
				rows.next();
				calls = rows.getInt(1);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to read the calls of " + routine.qualifiedName() + ": "
					+ e.getMessage(), e);
		}
		if (calls != times) {
			throw new AssertionError(routine.qualifiedName() + invocation.matchers().toString().replace('[', '(')
					.replace(']', ')') + " was expected " + timesOf(times) + " in this test, and was called "
					+ timesOf(calls));
		}
	}

	/**
	 * Only a stubbed routine records its calls: one that is not runs its real code, which records nothing, so a
	 * count of its calls would always be zero, and a {@code never()} would pass whatever happened.
	 */
	private void requireStubbed(RoutineMetadata routine) {
		String key = BodyRewriter.callKey(routine);
		boolean stubbed = stubsByPackage.getOrDefault(packageOf(routine), List.of()).stream()
				.anyMatch(stub -> BodyRewriter.callKey(routineOf(stub.invocation())).equals(key));
		if (!stubbed) {
			throw new IllegalStateException(routine.qualifiedName() + " has no stub in this test, so its calls are not"
					+ " recorded: only a stubbed routine records them, and verify can only count those");
		}
	}

	private static String jsonType(RoutineMetadata.Argument parameter) {
		String type = parameter.dataType();
		if (type.equals("DATE")) {
			return "DATE";
		}
		if (type.startsWith("TIMESTAMP")) {
			return "TIMESTAMP";
		}
		if (parameter.isNumeric()) {
			return "NUMBER";
		}
		return "VARCHAR2(4000)";
	}

	private static String timesOf(int count) {
		return count == 1 ? "1 time" : count + " times";
	}

	// ---------------------------------------------------------------- routines

	private RoutineMetadata routineOf(Invocation invocation) {
		Object describer = describerOf(invocation.mock().type());
		try {
			return Views.handlerOf(describer).describe(invocation.method());
		} catch (RuntimeException e) {
			if (noConnectionToSpare(e)) {
				throw noConnection(e);
			}
			throw e;
		}
	}

	/**
	 * A connection of the mocks' own, outside any transaction of the test: DDL commits. The pool has to have one to
	 * spare besides those the test holds, or the mocks wait for one until the pool gives up.
	 */
	private Connection connection() throws SQLException {
		try {
			return base.getConnection();
		} catch (SQLException e) {
			if (noConnectionToSpare(e)) {
				throw noConnection(e);
			}
			throw e;
		}
	}

	private static boolean noConnectionToSpare(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof java.sql.SQLTransientConnectionException) {
				return true;
			}
		}
		return false;
	}

	private static IllegalStateException noConnection(Throwable cause) {
		return new IllegalStateException("the mocks need a connection of their own, besides those the test holds, and"
				+ " the pool had none to spare: give the pool at least one connection more than the test uses at once",
				cause);
	}

	private Object describerOf(Class<?> type) {
		return describers.computeIfAbsent(type, t -> PlsqlPackages.create(t, base));
	}

	/** The matcher of each parameter of the routine, by its Oracle name, as the call would bind the arguments. */
	private static Map<String, Matcher> byParameter(RoutineMetadata routine, Invocation invocation) {
		Method method = invocation.method();
		Parameter[] javaParameters = method.getParameters();
		List<RoutineMetadata.Argument> parameters = routine.parameters();
		Map<String, Matcher> byParameter = new HashMap<>();
		for (int i = 0; i < javaParameters.length; i++) {
			String name = routine.byName() ? RoutineMetadata.oracleNameOf(method, javaParameters[i])
					: parameters.get(i).name();
			byParameter.put(name, invocation.matchers().get(i));
		}
		return byParameter;
	}

	private static String packageOf(RoutineMetadata routine) {
		if (routine.packageName() == null) {
			throw new IllegalStateException(routine.qualifiedName() + " is not in a package: the mocks only rewrite"
					+ " routines of packages yet");
		}
		return routine.schema() + "." + routine.packageName();
	}

	private void requireBegun() {
		if (edition == null) {
			throw new IllegalStateException("the mocks have not begun: call begin() first");
		}
	}

	private static String single(Connection connection, String sql) throws SQLException {
		List<String> values = strings(connection, sql);
		return values.isEmpty() ? null : values.get(0);
	}

	private static List<String> strings(Connection connection, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}
}
