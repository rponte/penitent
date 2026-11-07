package io.github.rponte.penitent.spring;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SQLExceptionTranslator;

import io.github.rponte.penitent.exception.PlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * A routine error arrives as {@link PlsqlRoutineRaisedException}, the same exception used without
 * Spring. Every other failure arrives as a {@link DataAccessException}, with the same classes a
 * {@link JdbcTemplate} would use.
 */
public final class SpringPlsqlExceptionTranslator implements PlsqlExceptionTranslator {

	private static final String TASK = "PlsqlPackages";

	private final SQLExceptionTranslator translator;

	public SpringPlsqlExceptionTranslator(DataSource dataSource) {
		this.translator = new JdbcTemplate(dataSource).getExceptionTranslator();
	}

	@Override
	public RuntimeException translate(PlsqlFailure failure) {
		if (failure.raisedByRoutine()) {
			return new PlsqlRoutineRaisedException(failure.sql(), failure.cause());
		}
		DataAccessException translated = translator.translate(TASK, failure.sql(), failure.cause());
		return translated != null ? translated : new UncategorizedSQLException(TASK, failure.sql(), failure.cause());
	}
}
