package io.github.rponte.penitent.internal.metadata;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

	private final Connection connection;

	public Dictionary(Connection connection) {
		this.connection = connection;
	}

	public <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
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
}
