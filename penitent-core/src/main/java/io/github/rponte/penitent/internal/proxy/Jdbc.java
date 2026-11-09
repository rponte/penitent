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

	/**
	 * {@code OracleTypes.PLSQL_BOOLEAN}, for every {@code BOOLEAN} the library binds, not the standard
	 * {@link Types#BOOLEAN}: before driver 23, {@code Types.BOOLEAN} fails with "Invalid column type: 16",
	 * while {@code PLSQL_BOOLEAN} exists since driver 12.2 and binds the same on 21 and 23, in both
	 * directions, null included. One SQL type for every version means one path, which the build tests on
	 * each driver. A null that comes back needs {@code wasNull()} on driver 21: see {@code ScalarCodec.read}.
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
	 * Runs the statement. An {@link AssertionError} from the driver is its own internal check failing in
	 * the middle of a response, so the connection is left at an unknown point of the protocol: it is
	 * aborted, so that the pool replaces it instead of handing it to the next call, which would fail with
	 * "Protocol violation" for no reason of its own. The error then goes the way of any other failure of
	 * the call, through the translator, as a {@code SQLException} that says what happened and keeps the
	 * driver's error as its cause. Only {@code AssertionError} is caught: any other {@code Error} is not
	 * the driver's to explain.
	 *
	 * <p>The known case is a cursor with a {@code BOOLEAN} column, the SQL type of 23ai: before driver
	 * 23, the driver does not know the type code of the column, 252, and fails while describing it. The
	 * database turns a {@code BOOLEAN} into a {@code NUMBER} for such a driver in a plain query, but not
	 * in a cursor opened in PL/SQL, which is the only kind the library reads, and the library never sees
	 * the query inside it: the conversion has to be in the query.
	 */
	static void execute(CallableStatement statement, String sql) {
		try {
			statement.execute();
		} catch (SQLException e) {
			throw new SqlFailure(sql, e, PlsqlFailure.Phase.EXECUTION);
		} catch (AssertionError e) {
			abortConnectionOf(statement, e);
			throw new SqlFailure(sql, new SQLException(driverFailure(e), e), PlsqlFailure.Phase.EXECUTION);
		}
	}

	private static String driverFailure(AssertionError e) {
		String message = String.valueOf(e.getMessage());
		if (message.contains("oacdty: 252")) {
			return "the Oracle driver before 23 cannot read a BOOLEAN column in a cursor (" + message
					+ "); convert it in the query, as in CASE WHEN flag THEN 'Y' WHEN NOT flag THEN 'N' END, which keeps"
					+ " null, and declare Boolean,"
					+ " or use driver 23. The connection was discarded";
		}
		return "the Oracle driver failed an internal check (" + message + "). The connection was discarded";
	}

	private static void abortConnectionOf(CallableStatement statement, AssertionError failure) {
		try {
			statement.getConnection().abort(Runnable::run);
		} catch (SQLException | RuntimeException e) {
			failure.addSuppressed(e);
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
			case "INTERVAL YEAR TO MONTH" -> ORACLE_INTERVALYM;
			default -> Types.VARCHAR;
		};
	}
}
