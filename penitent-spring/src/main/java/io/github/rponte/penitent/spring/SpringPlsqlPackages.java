package io.github.rponte.penitent.spring;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * {@link PlsqlPackages} for Spring users: the call takes part in the current transaction, and a
 * database failure arrives as a {@link DataAccessException}, as with a {@link JdbcTemplate}. The one
 * exception is a routine error, which arrives as {@link PlsqlRoutineRaisedException}, the same
 * exception used without Spring.
 */
public final class SpringPlsqlPackages {

	private SpringPlsqlPackages() {
	}

	public static <T> T create(Class<T> type, DataSource dataSource) {
		return create(type, dataSource, PlsqlSettings.defaults());
	}

	/** With converters that apply across the whole interface. See {@link PlsqlConverters}. */
	public static <T> T create(Class<T> type, DataSource dataSource, PlsqlConverters converters) {
		return create(type, dataSource, PlsqlSettings.defaults().withConverters(converters));
	}

	/**
	 * With {@link PlsqlSettings}. Without an exception translator in the settings, it is
	 * {@link SpringPlsqlExceptionTranslator}; with one, that one is used as it is.
	 */
	public static <T> T create(Class<T> type, DataSource dataSource, PlsqlSettings settings) {
		PlsqlSettings withTranslator = settings.exceptionTranslator().isPresent() ? settings
				: settings.withExceptionTranslator(new SpringPlsqlExceptionTranslator(dataSource));
		return PlsqlPackages.create(type, new TransactionAwareDataSourceProxy(dataSource), withTranslator);
	}
}
