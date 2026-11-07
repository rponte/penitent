package io.github.rponte.penitent.exception;

import java.sql.SQLException;

/**
 * The database rejected or failed to execute something. This is what the library throws by default
 * when there is no {@link PlsqlExceptionTranslator}; the original {@link SQLException} is kept as the
 * cause, with Oracle's code and message.
 */
public class PlsqlExecutionException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String sql;

	public PlsqlExecutionException(String sql, SQLException cause) {
		super(cause.getMessage(), cause);
		this.sql = sql;
	}

	/** The statement that failed; null when the failure happened before there was one, such as when obtaining the connection. */
	public String getSql() {
		return sql;
	}

	/**
	 * The error number, without the sign: {@code 20100} for an {@code ORA-20100}, even if the routine
	 * wrote {@code RAISE_APPLICATION_ERROR(-20100, ...)}.
	 */
	public int getCode() {
		return ((SQLException) getCause()).getErrorCode();
	}
}
