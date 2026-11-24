package io.github.rponte.penitent.internal.binding;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;

import java.time.ZonedDateTime;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec;
import io.github.rponte.penitent.internal.driver.DriverBoundary;
import io.github.rponte.penitent.internal.driver.DriverFailure;
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
			return connection.unwrap(OracleConnection.class).createOracleArray(typeName,
					values instanceof Object[] elements ? ScalarCodec.forComposite(elements) : values);
		} catch (IllegalArgumentException | ClassCastException | DriverFailure e) {
			// the driver converts the elements right here: this is bad data, not a database failure. Behind the
			// boundary, any runtime exception of the driver comes marked (#147)
			Throwable driver = e instanceof DriverFailure marked ? marked.getCause() : e;
			throw new PlsqlMappingException("an element does not fit in " + typeName + ": " + driver.getMessage(),
					driver);
		}
	}

	/**
	 * The elements, to be turned into {@code javaElement}. {@code getArray()} returns a {@code
	 * TIMESTAMP WITH TIME ZONE} without the region name, and a {@code DATE} or {@code TIMESTAMP} as a
	 * {@code Timestamp}, through the JVM's time zone; only the driver's own types still carry the region
	 * and the exact local time. {@code getResultSet()} also returns them, but yields nulls for an
	 * {@code INDEX BY}.
	 */
	public static Object[] elements(Array array, Class<?> javaElement) throws SQLException {
		// the driver's own class, behind the boundary's proxy (#147)
		return (javaElement == ZonedDateTime.class || ScalarCodec.isLocalTemporal(javaElement))
				&& DriverBoundary.real(array) instanceof ARRAY oracle
						? oracleArray(oracle)
						: (Object[]) array.getArray();
	}

	/** A call on the driver's own class, which the boundary's proxy cannot stand for: guarded as at the boundary. */
	private static Object[] oracleArray(ARRAY array) throws SQLException {
		return DriverBoundary.guarded("ARRAY.getOracleArray", null, array::getOracleArray);
	}
}
