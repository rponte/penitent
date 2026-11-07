package io.github.rponte.penitent.exception;

/**
 * Decides which exception the caller receives when the database fails. It exists so that the
 * integration with a framework, which has its own exception hierarchy, does not have to live in the
 * core.
 *
 * @see DefaultPlsqlExceptionTranslator
 */
@FunctionalInterface
public interface PlsqlExceptionTranslator {

	/**
	 * @param failure the failure, and the point of the call where it happened
	 * @return the exception to throw
	 */
	RuntimeException translate(PlsqlFailure failure);
}
