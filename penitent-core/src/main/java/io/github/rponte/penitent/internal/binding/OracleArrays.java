package io.github.rponte.penitent.internal.binding;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;

import java.time.ZonedDateTime;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import oracle.jdbc.OracleConnection;
import oracle.sql.ARRAY;

/**
 * Creates VARRAY / nested table instances.
 *
 * <p>Through {@code unwrap()}, not a cast: {@code oracle.sql.ArrayDescriptor} is pre-JDBC 4 API and
 * casts the connection straight to {@code OracleConnection}, so it breaks behind any DataSource that
 * wraps the connection. That was the case with HikariCP, whose {@code HikariProxyConnection} is not
 * an {@code OracleConnection}.
 */
public final class OracleArrays {

	private OracleArrays() {
	}

	public static Array create(Connection connection, String typeName, Object values) throws SQLException {
		try {
			return connection.unwrap(OracleConnection.class).createOracleArray(typeName, values);
		} catch (IllegalArgumentException | ClassCastException e) {
			// the driver converts the elements right here: this is bad data, not a database failure
			throw new PlsqlMappingException("an element does not fit in " + typeName + ": " + e.getMessage(), e);
		}
	}

	/**
	 * The elements, to be turned into {@code javaElement}. {@code getArray()} returns a {@code
	 * TIMESTAMP WITH TIME ZONE} without the region name, which only the driver's own types still
	 * carry. {@code getResultSet()} also returns them, but yields nulls for an {@code INDEX BY}.
	 */
	public static Object[] elements(Array array, Class<?> javaElement) throws SQLException {
		return javaElement == ZonedDateTime.class && array instanceof ARRAY oracle
				? oracle.getOracleArray()
				: (Object[]) array.getArray();
	}
}
