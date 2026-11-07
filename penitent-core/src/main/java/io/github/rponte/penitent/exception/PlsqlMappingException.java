package io.github.rponte.penitent.exception;

/**
 * The interface and the database do not match. The message always says what was looked up and what
 * exists, because the alternative is the user getting an {@code ORA-06550} and having to guess.
 */
public class PlsqlMappingException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public PlsqlMappingException(String message) {
		super(message);
	}

	public PlsqlMappingException(String message, Throwable cause) {
		super(message, cause);
	}
}
