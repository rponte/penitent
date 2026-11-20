package io.github.rponte.penitent.internal.driver;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure;

/**
 * The major versions of the driver and of the database of a connection, and what follows from them. It is read for
 * each call, not kept: the same proxy may serve connections of different pools, and so of different drivers or
 * databases. Reading it costs no round trip: the driver knows both versions once connected.
 *
 * @param driver the driver's major version, such as 19, 21 or 23
 * @param database the database's major version, such as 19, 21 or 23
 */
public record DriverProfile(int driver, int database) {

	/** The profile of the connection; a failure to read it is a failure of the call's preparation. */
	public static DriverProfile of(Connection connection) {
		try {
			DatabaseMetaData metadata = connection.getMetaData();
			return new DriverProfile(metadata.getDriverMajorVersion(), metadata.getDatabaseMajorVersion());
		} catch (SQLException e) {
			throw new SqlFailure(null, e, PlsqlFailure.Phase.PREPARATION);
		}
	}

	/** Oracle's JSON type, of 21c, crosses with a type code of the driver's own, which only drivers 21 and later know. */
	public boolean knowsJson() {
		return driver >= 21;
	}

	/** A database with types the driver does not know, such as JSON: a driver before 21 and a database of 21c or later. */
	public boolean olderDriverThanDatabase() {
		return driver < 21 && database >= 21;
	}
}
