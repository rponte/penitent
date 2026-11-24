package io.github.rponte.penitent.internal.driver;

import java.sql.Connection;
import java.sql.SQLException;

import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure;

/**
 * A runtime exception or an {@code AssertionError} that came out of the Oracle driver, which JDBC lets report a failure
 * only as a {@code SQLException}: the driver failed itself (#147). {@link DriverBoundary} marks it so, with the
 * operation it failed in and the connection of the call, and keeps the original as the cause. The call then turns it
 * into the library's exception at the end of each phase, by where the driver failed:
 * <ul>
 * <li><b>binding a value,</b> which the driver converts in the client before anything is sent: a statement's setter or
 * {@code registerOutParameter}, or building an object type or a collection, is a {@code PlsqlMappingException} (see
 * {@link #ofBinding}), and the connection is kept;</li>
 * <li><b>anywhere else,</b> reading the results, writing a LOB, preparing or closing the statement, reading the
 * dictionary, is the driver's own failure, a {@code PlsqlExecutionException} and never the routine's, and the
 * connection is discarded (see {@link #ofCall}): the driver may have failed in the middle of the protocol, which a pool
 * would otherwise hand to the next call, to fail it with "Protocol violation", as #137 measured for
 * {@code execute()}.</li>
 * </ul>
 * Unchecked, so that no {@code catch (SQLException e)} on the way takes it for a database's error.
 */
public final class DriverFailure extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String operation;
	private final transient Connection connection;

	DriverFailure(String operation, Throwable failure, Connection connection) {
		super(operation + ": " + describe(failure), failure);
		this.operation = operation;
		this.connection = connection;
	}

	/** The interface and the method the driver failed in, as in {@code CallableStatement.setObject}. */
	public String operation() {
		return operation;
	}

	/**
	 * Whether the driver failed converting a value in the client, before anything was sent: a setter of a statement,
	 * {@code registerOutParameter}, or building an object type or a collection.
	 */
	public boolean inBinding() {
		String method = operation.substring(operation.lastIndexOf('.') + 1);
		boolean statement = operation.contains("Statement.");
		return (statement && (method.startsWith("set") || method.equals("registerOutParameter")))
				|| method.equals("createStruct") || method.equals("createOracleArray") || method.equals("createArrayOf");
	}

	/** The driver's exception, as in {@code IllegalArgumentException: Overflow}. */
	public String described() {
		return describe(getCause());
	}

	/**
	 * A value the driver refused while binding it, {@code what} being the parameter, as in {@code P_VALUE of
	 * SCHEMA.PKG.ROUTINE}: the connection is kept.
	 */
	public PlsqlMappingException ofBinding(String what) {
		return new PlsqlMappingException(what + ": the driver refused it while binding it, in " + operation
				+ ", at a limit of its own: " + described(), getCause());
	}

	/**
	 * The driver's own failure in the call, in {@code phase}, {@code where} saying which call it was, as in
	 * {@code SCHEMA.PKG.ROUTINE}: the connection is discarded first.
	 */
	public SqlFailure ofCall(String where, String sql, PlsqlFailure.Phase phase) {
		discardConnection();
		return new SqlFailure(sql, new SQLException(where + ": the Oracle driver failed itself in " + operation + " ("
				+ described() + "). The connection was discarded", getCause()), phase);
	}

	/** Aborts the connection of the call, so that the pool replaces it; a failure to abort is kept as suppressed. */
	public void discardConnection() {
		if (connection == null) {
			return;
		}
		try {
			connection.abort(Runnable::run);
		} catch (SQLException | RuntimeException e) {
			getCause().addSuppressed(e);
		}
	}

	private static String describe(Throwable failure) {
		String message = failure.getMessage();
		return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
	}
}
