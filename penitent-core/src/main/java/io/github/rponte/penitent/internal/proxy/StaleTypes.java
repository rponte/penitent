package io.github.rponte.penitent.internal.proxy;

import java.sql.SQLException;

import io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure;

/**
 * The errors of a type that changed in the database after a connection first used it. The driver keeps, in each
 * connection, the type as it read it then, in a cache that its public methods do not clear: only a new connection reads
 * the new type, which neither the library nor a new proxy can bring about. So the error stays Oracle's, with its code,
 * which a pool such as HikariCP can act on, and says what to do.
 */
final class StaleTypes {

	/** {@code ORA-17049}: the driver's version of a type is not the database's. */
	static final int INCONSISTENT_TYPES = 17049;

	/**
	 * What the database answers to a value sent in an older shape of its type: {@code ORA-03101: invalid input data} on
	 * 19c and 23ai, and {@code ORA-00600 [kope2_readstr232]}, an internal error, on 21c. Neither says the type changed:
	 * the library claims it only when the dictionary shows that the method no longer fits the type.
	 */
	static final java.util.Set<Integer> OLDER_SHAPE = java.util.Set.of(3101, 600);

	private StaleTypes() {
	}

	/** {@code failure}, its {@code ORA-17049} explained, keeping the code, the SQLState and the original as the cause. */
	static SqlFailure explained(SqlFailure failure) {
		SQLException cause = failure.failure().cause();
		if (cause.getErrorCode() != INCONSISTENT_TYPES) {
			return failure;
		}
		SQLException explained = new SQLException(cause.getMessage() + "\nThe driver keeps, in each connection, a type"
				+ " as it was when the connection first used it: the type changed in the database since, and only a new"
				+ " connection reads it. Restart the application, or renew the pool's connections; see \"Changing PL/SQL"
				+ " while the application runs\" in the documentation", cause.getSQLState(), cause.getErrorCode(), cause);
		return new SqlFailure(failure.failure().sql(), explained, failure.failure().phase());
	}
}
