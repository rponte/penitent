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
	 */
	static final int ORACLE_PLSQL_BOOLEAN = 252;

	private Jdbc() {
	}

	static void execute(CallableStatement statement, String sql) {
		try {
			statement.execute();
		} catch (SQLException e) {
			throw new SqlFailure(sql, e, PlsqlFailure.Phase.EXECUTION);
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
