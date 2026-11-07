package io.github.rponte.penitent.exception;

/**
 * What happens without a framework: {@link PlsqlRoutineRaisedException} for what came from the
 * routine, and {@link PlsqlExecutionException} for every other database failure.
 */
public final class DefaultPlsqlExceptionTranslator implements PlsqlExceptionTranslator {

	@Override
	public RuntimeException translate(PlsqlFailure failure) {
		return failure.raisedByRoutine()
				? new PlsqlRoutineRaisedException(failure.sql(), failure.cause())
				: new PlsqlExecutionException(failure.sql(), failure.cause());
	}
}
