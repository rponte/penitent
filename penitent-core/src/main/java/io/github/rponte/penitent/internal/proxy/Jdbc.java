package io.github.rponte.penitent.internal.proxy;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;

import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.internal.binding.OracleArrays;
import io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/** What a routine call and a block call share on the JDBC side: the SQL types, and running the statement. */
final class Jdbc {

	/** From {@code OracleTypes}, repeated here so that the driver stays confined to {@link OracleArrays}. */
	static final int ORACLE_BINARY_FLOAT = 100;
	static final int ORACLE_BINARY_DOUBLE = 101;
	static final int ORACLE_TIMESTAMPTZ = -101;
	static final int ORACLE_TIMESTAMPLTZ = -102;
	static final int ORACLE_INTERVALYM = -103;
	static final int ORACLE_INTERVALDS = -104;
	/** {@code OracleTypes.JSON}: the JSON type of Oracle 21c and later, which drivers 21 and later know. */
	static final int ORACLE_JSON = 2016;

	/**
	 * {@code OracleTypes.PLSQL_BOOLEAN}, for every {@code BOOLEAN} the library binds, not the standard
	 * {@link Types#BOOLEAN}: before driver 23, {@code Types.BOOLEAN} fails with "Invalid column type: 16",
	 * while {@code PLSQL_BOOLEAN} exists since driver 12.2 and binds the same on 21 and 23, in both
	 * directions, null included. One SQL type for every version means one path, which the build tests on
	 * each driver. A null that comes back needs {@code wasNull()} on driver 21: see {@code ScalarCodec.read}.
	 * The one exception is driver 23 or later against a database before 23ai, which reads a null that
	 * comes back as true or false: see {@link NullBooleanOutputs}.
	 *
	 * <p>The other ways do not hold on every version. Driver 21 sends {@code setBoolean} as a
	 * {@code NUMBER}, which a PL/SQL {@code BOOLEAN} refuses with {@code ORA-06550}, as it refuses
	 * {@code setInt}. Driver 23 chooses for {@code setBoolean} and {@code Types.BOOLEAN}: the native type,
	 * or a {@code NUMBER} when the connection properties {@code oracle.jdbc.sendBooleanAsNativeBoolean} or
	 * {@code oracle.jdbc.sendBooleanInPLSQL} are off, or, outside PL/SQL, when the database is older than
	 * 23ai. {@code PLSQL_BOOLEAN} always goes as the native type, whatever those properties and the
	 * database version. None of this applies to a column of a cursor, which the driver reads by the type
	 * the database describes, with nothing bound: see {@link #execute}.
	 */
	static final int ORACLE_PLSQL_BOOLEAN = 252;

	private Jdbc() {
	}

	/**
	 * Runs the statement, and treats anything but a {@code SQLException} that the driver throws as the driver's own
	 * failure.
	 *
	 * <p><b>Why any {@code RuntimeException}, and not only some.</b> JDBC lets a driver report a failure of the
	 * database or of the call only as a {@code SQLException}. So an {@link AssertionError} or a
	 * {@code RuntimeException} out of {@code execute()} is the driver failing itself, in the middle of a response,
	 * and the connection is left at an unknown point of the protocol. Nothing but the driver runs inside
	 * {@code execute()}, so the rule cannot catch a failure of the library or of the application. Which exception it
	 * is depends on the driver's state, not on the cause: driver 19 fails a cursor with a JSON column with an
	 * {@code AssertionError} or, measured in the same test class, with an {@code ArrayIndexOutOfBoundsException}.
	 * Catching only the first let the second through, and the broken connection went back to the pool, to fail the
	 * next, unrelated call with "Protocol violation".
	 *
	 * <p><b>What it does.</b> The connection is aborted, so that the pool replaces it instead of handing it to the
	 * next call. The failure then goes the way of any other failure of the call, through the translator, as a
	 * {@code SQLException} that says what happened and keeps the driver's exception as its cause. Any other
	 * {@code Error}, such as an {@code OutOfMemoryError}, is not the driver's to explain, and goes as it is.
	 *
	 * <p><b>The known cases,</b> which the message explains, are columns of a type the driver does not know, in a
	 * cursor opened in PL/SQL, the only kind the library reads, and whose query it never sees, so the conversion has
	 * to be in the query:
	 * <ul>
	 * <li>a {@code BOOLEAN} column, the SQL type of 23ai, before driver 23: type code 252;</li>
	 * <li>a {@code JSON} column, the type of 21c, before driver 21: the driver reports no type code, so the message
	 * says "such as JSON", for a driver before 21 and a database of 21c or later.</li>
	 * </ul>
	 */
	static void execute(CallableStatement statement, String sql) {
		try {
			statement.execute();
		} catch (SQLException e) {
			throw new SqlFailure(sql, e, PlsqlFailure.Phase.EXECUTION);
		} catch (AssertionError | RuntimeException e) {
			// the driver's own failure, as the Javadoc explains; the versions are read first, then the connection aborted
			String failure = driverFailure(e, statement);
			abortConnectionOf(statement, e);
			throw new SqlFailure(sql, new SQLException(failure, e), PlsqlFailure.Phase.EXECUTION);
		}
	}

	private static String driverFailure(Throwable e, CallableStatement statement) {
		String message = String.valueOf(e.getMessage());
		if (message.contains("oacdty: 252")) {
			return "the Oracle driver before 23 cannot read a BOOLEAN column in a cursor (" + message
					+ "); convert it in the query, as in CASE WHEN flag THEN 'Y' WHEN NOT flag THEN 'N' END, which keeps"
					+ " null, and declare Boolean,"
					+ " or use driver 23. The connection was discarded";
		}
		if (olderDriverThanDatabase(statement)) {
			// the known case is a cursor with a JSON column, the type of 21c, which driver 19 fails to describe with
			// oacdty: 0, before the library sees any column; the code does not name the type, hence "such as"
			return "the Oracle driver failed an internal check (" + message + "): a column of a type that driver "
					+ driverMajorVersion(connectionOf(statement)) + " does not know, such as JSON, makes it fail: convert"
					+ " it in the query, as in JSON_SERIALIZE(doc RETURNING CLOB), or use driver 21 or later. The"
					+ " connection was discarded";
		}
		return "the Oracle driver failed an internal check (" + message + "). The connection was discarded";
	}

	/** A driver before 21 talking to a database of 21c or later, which has types the driver does not know. */
	private static boolean olderDriverThanDatabase(CallableStatement statement) {
		try {
			java.sql.DatabaseMetaData metadata = statement.getConnection().getMetaData();
			return metadata.getDriverMajorVersion() < 21 && metadata.getDatabaseMajorVersion() >= 21;
		} catch (SQLException | RuntimeException e) {
			return false;
		}
	}

	private static java.sql.Connection connectionOf(CallableStatement statement) {
		try {
			return statement.getConnection();
		} catch (SQLException e) {
			throw new SqlFailure(null, e, PlsqlFailure.Phase.EXECUTION);
		}
	}

	private static void abortConnectionOf(CallableStatement statement, Throwable failure) {
		try {
			statement.getConnection().abort(Runnable::run);
		} catch (SQLException | RuntimeException e) {
			failure.addSuppressed(e);
		}
	}

	/** The driver's major version, such as 19, 21 or 23, as the connection reports it. */
	static int driverMajorVersion(java.sql.Connection connection) {
		try {
			return connection.getMetaData().getDriverMajorVersion();
		} catch (java.sql.SQLException e) {
			throw new io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure(null, e,
					io.github.rponte.penitent.exception.PlsqlFailure.Phase.PREPARATION);
		}
	}

		static int sqlTypeOf(Argument argument) {
		return switch (argument.dataType()) {
			// RECORD and %ROWTYPE travel as STRUCT, with the qualified name
			case "OBJECT", "PL/SQL RECORD" -> Types.STRUCT;
			case "TABLE", "VARRAY", "PL/SQL TABLE" -> Types.ARRAY;
			case "REF CURSOR" -> Types.REF_CURSOR;
			// BOOLEAN became a first-class SQL type in 23ai; before that it could only be
			// exposed through a wrapper, which is why older schemas often have _WRAP packages
			case "BOOLEAN", "PL/SQL BOOLEAN" -> ORACLE_PLSQL_BOOLEAN;
			// numerics always with their real type: as VARCHAR, the database would format the value
			// using the session's NLS settings, and "1,5" would no longer be a number on this side
			case "NUMBER", "FLOAT" -> Types.NUMERIC;
			case "BINARY_DOUBLE" -> ORACLE_BINARY_DOUBLE;
			case "BINARY_FLOAT" -> ORACLE_BINARY_FLOAT;
			case "DATE" -> Types.DATE;
			case "TIMESTAMP" -> Types.TIMESTAMP;
			case "TIMESTAMP WITH TIME ZONE" -> ORACLE_TIMESTAMPTZ;
			case "TIMESTAMP WITH LOCAL TIME ZONE" -> ORACLE_TIMESTAMPLTZ;
			// as VARCHAR, a CLOB stops at 32 KB, a BLOB does not bind (PLS-00382), and a RAW comes back
			// as hexadecimal text
			case "CLOB" -> Types.CLOB;
			case "NCLOB" -> Types.NCLOB;
			case "BLOB" -> Types.BLOB;
			case "RAW" -> Types.BINARY; // OracleTypes.RAW: as VARBINARY, ORA-17012
			case "INTERVAL DAY TO SECOND" -> ORACLE_INTERVALDS;
			// as VARCHAR, a JSON parameter yields PLS-00306 and a JSON return value PLS-00382: the database does not
			// convert text to JSON for a bind, so it goes with its own type, as the JSON text
			case "JSON" -> ORACLE_JSON;
			case "INTERVAL YEAR TO MONTH" -> ORACLE_INTERVALYM;
			default -> Types.VARCHAR;
		};
	}

	/**
	 * {@code value} bound as a {@code BINARY_DOUBLE} or a {@code BINARY_FLOAT}, the Oracle types that hold {@code NaN}
	 * and the infinities, through the driver's own methods: drivers 21 and 23 also take {@code setObject} with the
	 * type's code, and driver 19 refuses it as an invalid column type.
	 */
	static void setBinaryFloatingPoint(java.sql.PreparedStatement statement, int position, Number value,
			String dataType) throws java.sql.SQLException {
		oracle.jdbc.OraclePreparedStatement oracle = statement.unwrap(oracle.jdbc.OraclePreparedStatement.class);
		if ("BINARY_FLOAT".equals(dataType)) {
			oracle.setBinaryFloat(position, value.floatValue());
		} else {
			oracle.setBinaryDouble(position, value.doubleValue());
		}
	}
}
