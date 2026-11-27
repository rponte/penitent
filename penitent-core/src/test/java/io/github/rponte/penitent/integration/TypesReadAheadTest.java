package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackages;

/**
 * The types a type refers to are read ahead, a level at a time (#169), and reading ahead raises nothing: a failure
 * there stops it, and what it did not read is read when it is asked for, as before.
 */
class TypesReadAheadTest extends OracleIntegrationTest {

	private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
		try {
			return method.invoke(target, arguments);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	/** A DataSource whose first query of several schema typecodes, as reading ahead asks, fails. */
	private static DataSource failingOnceReadingAhead(AtomicInteger failed) {
		return (DataSource) Proxy.newProxyInstance(TypesReadAheadTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (p, m, a) -> {
					Object result = invoke(DATA_SOURCE, m, a);
					if (!(result instanceof Connection connection)) {
						return result;
					}
					return Proxy.newProxyInstance(TypesReadAheadTest.class.getClassLoader(),
							new Class<?>[] { Connection.class }, (cp, cm, ca) -> {
								Object prepared = invoke(connection, cm, ca);
								String sql = ca != null && ca.length > 0 && ca[0] instanceof String s
										? s.toLowerCase(Locale.ROOT) : "";
								if (cm.getName().equals("prepareStatement") && sql.contains("from all_types")
										&& sql.contains("type_name in (?, ?") && failed.get() == 0) {
									PreparedStatement statement = (PreparedStatement) prepared;
									return Proxy.newProxyInstance(TypesReadAheadTest.class.getClassLoader(),
											new Class<?>[] { PreparedStatement.class }, (sp, sm, sa) -> {
												if (sm.getName().equals("executeQuery")) {
													failed.incrementAndGet();
													throw new SQLException("reading ahead fails, on purpose");
												}
												return invoke(statement, sm, sa);
											});
								}
								return prepared;
							});
				});
	}

	@Test
	@DisplayName("a failure reading ahead raises nothing: the types it did not read are read when asked for")
	void failure_reading_ahead() {
		AtomicInteger failed = new AtomicInteger();
		ObjectTypesTest.Composites composites = PlsqlPackages.create(ObjectTypesTest.Composites.class,
				failingOnceReadingAhead(failed));
		ObjectTypesTest.Customer customer = new ObjectTypesTest.Customer(
				List.of(new ObjectTypesTest.Address("B", 2)), 7L, "Ann Doe", true, true, LocalDate.parse("1990-05-20"),
				ObjectTypesTest.Status.NEW, new ObjectTypesTest.Address("A", 1), new String[] { "Annie" });

		assertThat(composites.echoCustomer(customer)).usingRecursiveComparison().isEqualTo(customer);
		assertThat(failed).as("the query read ahead failed").hasValue(1);
	}
}
