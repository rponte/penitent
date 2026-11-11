package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.rponte.penitent.internal.log.CallLog;

/**
 * Warns, once per method, that a null PL/SQL {@code BOOLEAN} that comes back will read as true or
 * false: as a return value, an {@code OUT} or {@code IN OUT}, or a block's {@code :result}, with driver
 * 23 or later against a database before Oracle 23ai. True and false read right, so the call goes on.
 *
 * <p>The cause is in the driver, read in its bytecode and in the bytes on the wire, in 23.26.3 and
 * 23.26.4. For a null bind of type 252 ({@code PLSQL_BOOLEAN}), {@code T4CTTIrxd.marshal} checks the
 * protocol version, and below 17, a database before 23ai, it sends the null as {@code FD 01}, where
 * driver 21 sends a plain null. The database then answers with a one-byte value and the null indicator,
 * and {@code T4CBooleanAccessor} decides by the length alone, discarding the indicator: the null reads
 * as whatever that byte is. Driver 21 has the same accessor, but gets a value of length zero.
 *
 * <p>Nothing on the JDBC side avoids it: a {@code setNull} or a {@code setBoolean} before
 * {@code registerOutParameter}, an {@code IN OUT} with a null, and the connection properties
 * {@code oracle.jdbc.sendBooleanAsNativeBoolean} and {@code oracle.jdbc.sendBooleanInPLSQL} (which, off,
 * break a null going in) all still read true or false. The library cannot see the indicator, so it
 * cannot read the value right either, and the ways around it are the user's: the driver of the
 * database's version, or the PL/SQL handing the value back as {@code VARCHAR2} or {@code NUMBER}, which
 * the library already reads into a {@code Boolean}, null included. A routine that wrapped the call in a
 * block of its own to do that conversion would not reach a {@code @PlsqlBlock}, whose PL/SQL is the
 * user's, and would change the SQL of every call for one combination of versions; it is not done.
 *
 * <p>A {@code BOOLEAN} that only goes in is not affected: it reaches such a database right, null
 * included, which the tests of the parameters show on every driver. {@code NullBooleanOutputTest} pins the driver's behavior: if a
 * future driver fixes it, that test fails, and this class can go.
 */
final class NullBooleanOutputs {

	/** Each method is checked once, whatever the answer: the check reads the connection's metadata. */
	private static final Set<Method> CHECKED = ConcurrentHashMap.newKeySet();

	private NullBooleanOutputs() {
	}

	/**
	 * @param what the routine or block and how the BOOLEAN comes back, as in
	 *        {@code "PKG.F returns a BOOLEAN"}
	 */
	static void warnIfMisread(Method method, String what, Connection connection) {
		if (!CHECKED.add(method)) {
			return;
		}
		try {
			DatabaseMetaData metadata = connection.getMetaData();
			int database = metadata.getDatabaseMajorVersion();
			if (metadata.getDriverMajorVersion() >= 23 && database < 23) {
				CallLog.nullBooleanOutputMisread(what, metadata.getDriverVersion(), database);
			}
		} catch (SQLException e) {
			// only a warning: without the versions, there is nothing to say, and the call goes on
		}
	}
}
