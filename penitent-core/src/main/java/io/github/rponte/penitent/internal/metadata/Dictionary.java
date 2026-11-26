package io.github.rponte.penitent.internal.metadata;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import io.github.rponte.penitent.exception.PlsqlFailure;

/**
 * Dictionary queries, on the connection of the call in progress.
 *
 * <p>Always on that one, never on another connection from the pool: holding one connection while
 * asking for a second can deadlock with a small pool, and the dictionary must be the one seen by the
 * same session, which has the same edition and the same {@code current_schema} as the call.
 */
public final class Dictionary {

	@FunctionalInterface
	public interface RowMapper<T> {
		T map(ResultSet rs, int rowNum) throws SQLException;
	}

	/**
	 * A database failure crossing code that cannot declare {@link SQLException}, such as the caches'
	 * {@code computeIfAbsent}. The handler translates it, at the edge of the call.
	 */
	public static final class SqlFailure extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final String sql;
		private final PlsqlFailure.Phase phase;

		public SqlFailure(String sql, SQLException cause, PlsqlFailure.Phase phase) {
			super(cause);
			this.sql = sql;
			this.phase = phase;
		}

		public PlsqlFailure failure() {
			return new PlsqlFailure(sql, (SQLException) getCause(), phase);
		}
	}

	/**
	 * The rows a dictionary query brings in each round trip (#169). The driver's default, 10, costs one more round
	 * trip for each further 10 rows, as an overloaded routine's parameters have. The rows are small, a few names and
	 * types, and a query's result lives only while it is read, so 100 costs little memory, and fixed: no setting,
	 * since nothing about the application changes what fits.
	 */
	private static final int FETCH_SIZE = 100;

	private final Connection connection;

	public Dictionary(Connection connection) {
		this.connection = connection;
	}

	public <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setFetchSize(FETCH_SIZE);
			for (int i = 0; i < parameters.length; i++) {
				statement.setObject(i + 1, parameters[i]);
			}
			try (ResultSet rs = statement.executeQuery()) {
				List<T> rows = new ArrayList<>();
				while (rs.next()) {
					rows.add(mapper.map(rs, rows.size()));
				}
				return rows;
			}
		} catch (SQLException e) {
			throw new SqlFailure(sql, e, PlsqlFailure.Phase.DICTIONARY);
		} catch (io.github.rponte.penitent.internal.driver.DriverFailure e) {
			throw e.ofCall("reading the dictionary", sql, PlsqlFailure.Phase.DICTIONARY);
		}
	}

	public List<String> strings(String sql, Object... parameters) {
		return query(sql, (rs, n) -> rs.getString(1), parameters);
	}

	/** For queries that always return one row, such as a {@code count(*)}. */
	int count(String sql, Object... parameters) {
		return query(sql, (rs, n) -> rs.getInt(1), parameters).get(0);
	}

	public String currentSchema() {
		return strings("select sys_context('userenv', 'current_schema') from dual").get(0);
	}

	/**
	 * What {@code DBMS_UTILITY.NAME_RESOLVE} said of one name, as PL/SQL would resolve it ({@code context => 1}): the
	 * owner, {@code part1} for a package, {@code part2} for a standalone routine, the type of {@code part1} (7, a
	 * procedure; 8, a function; 9, a package), or the error it raised, {@code ORA-06564} for a name that does not
	 * resolve. {@code error} is 0 when it resolved.
	 */
	public record Resolution(int error, String message, String schema, String part1, String part2, String dblink,
			int type) {
	}

	/**
	 * Each of {@code names}, resolved by Oracle itself, in one round trip (#174): {@code DBMS_UTILITY.NAME_RESOLVE},
	 * which follows synonyms, of synonyms too, in Oracle's own order, in the session's {@code current_schema} and
	 * edition, with the caller's privileges. Each name in a block of its own, so that one that does not resolve does
	 * not stop the others; the answers come back as {@code OUT} binds, never as text to parse. The names go as
	 * PL/SQL would take them: quoted, to be taken as they are.
	 */
	public List<Resolution> resolve(List<String> names) {
		StringBuilder block = new StringBuilder("begin\n");
		for (int i = 0; i < names.size(); i++) {
			block.append("""
					  begin
					    dbms_utility.name_resolve(?, 1, ?, ?, ?, ?, ?, ?);
					  exception when others then
					    ? := -sqlcode; ? := sqlerrm;
					  end;
					""");
		}
		String sql = block.append("end;").toString();
		try (CallableStatement statement = connection.prepareCall(sql)) {
			for (int i = 0; i < names.size(); i++) {
				int first = 1 + i * BINDS_PER_NAME;
				statement.setString(first, names.get(i));
				for (int out = first + 1; out <= first + 4; out++) {
					statement.registerOutParameter(out, Types.VARCHAR);
				}
				statement.registerOutParameter(first + 5, Types.NUMERIC);
				statement.registerOutParameter(first + 6, Types.NUMERIC);
				statement.registerOutParameter(first + 7, Types.NUMERIC);
				statement.registerOutParameter(first + 8, Types.VARCHAR);
			}
			statement.execute();
			List<Resolution> resolutions = new ArrayList<>();
			for (int i = 0; i < names.size(); i++) {
				int first = 1 + i * BINDS_PER_NAME;
				// the error is assigned only when NAME_RESOLVE raised one: null, read as 0, when it resolved
				int error = statement.getInt(first + 7);
				resolutions.add(error != 0
						? new Resolution(error, statement.getString(first + 8), null, null, null, null, 0)
						: new Resolution(0, null, statement.getString(first + 1), statement.getString(first + 2),
								statement.getString(first + 3), statement.getString(first + 4),
								statement.getInt(first + 5)));
			}
			return resolutions;
		} catch (SQLException e) {
			throw new SqlFailure(sql, e, PlsqlFailure.Phase.DICTIONARY);
		} catch (io.github.rponte.penitent.internal.driver.DriverFailure e) {
			throw e.ofCall("reading the dictionary", sql, PlsqlFailure.Phase.DICTIONARY);
		}
	}

	/** In each name's block: the name; schema, part1, part2, dblink, its type, its object number; the error and its text. */
	private static final int BINDS_PER_NAME = 9;
}
