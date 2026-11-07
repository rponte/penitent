package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/** Each error is actually raised, because it is the code Oracle returns that decides the exception. */
class RoutineErrorsTest extends OracleIntegrationTest {

	record Item(Integer value) {
	}

	@PlsqlPackage("FIXTURE_ERRORS")
	interface Errors {
		void triggerError(String error);

		List<Item> cursorWithError();

		@PlsqlRoutine("TRIGGER_ERROR")
		void typeTheDriverRefuses(java.net.URI error);
	}

	private final Errors errors = pkg(Errors.class);

	@ParameterizedTest(name = "{0} -> ORA-{1}")
	@CsvSource({
			"RAE, 20200", "RAE_OUT_OF_RANGE, 21000", "NO_DATA_FOUND, 1403", "TOO_MANY_ROWS, 1422",
			"ZERO_DIVIDE, 1476", "VALUE_ERROR, 6502", "INVALID_NUMBER, 1722", "INVALID_DATE, 1841",
			"OWN_EXCEPTION, 6510", "SUBSCRIPT, 6533", "NULL_COLLECTION, 6531", "NULL_OBJECT, 6530",
			"CASE_NOT_FOUND, 6592", "DUPLICATE_KEY, 1", "NOT_NULL, 1400", "CHECK, 2290", "FK_WITHOUT_PARENT, 2291",
			"FK_WITH_CHILD, 2292", "VALUE_TOO_LARGE, 12899", "DYNAMIC_TABLE, 942", "DYNAMIC_SYNTAX, 900" })
	@DisplayName("the routine error is a PlsqlRoutineRaisedException, with the unsigned code")
	void routine_error(String error, int code) {
		assertThatThrownBy(() -> errors.triggerError(error))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(code);
					assertThat(e.getMessage()).contains(String.format("ORA-%05d", code));
					assertThat(e.getReason()).isNotBlank().doesNotContain("ORA-");
				});
	}

	@Test
	@DisplayName("the reason of a native error is the Oracle text, without the ORA prefix and without the stack")
	void native_error_reason() {
		assertThatThrownBy(() -> errors.triggerError("NO_DATA_FOUND"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class,
						// the text follows the session language, so the proof is that it comes from the first line of the message
						e -> assertThat(e.getMessage()).startsWith("ORA-01403: " + e.getReason() + "\n"));
	}

	@Test
	@DisplayName("EXCEPTION_INIT with RAISE: the code is the declared one, and the reason is empty")
	void exception_init() {
		assertThatThrownBy(() -> errors.triggerError("EXCEPTION_INIT"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(20200);
					assertThat(e.getReason()).isEmpty();
					assertThat(e.getMessage()).startsWith("ORA-20200:");
				});
	}

	@Test
	@DisplayName("ORA-06550 is always a call failure, even when it comes from dynamic SQL inside the routine")
	void dynamic_plsql() {
		assertThatThrownBy(() -> errors.triggerError("DYNAMIC_PLSQL"))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.satisfies(e -> assertThat(((PlsqlExecutionException) e).getCode()).isEqualTo(6550));
	}

	@Test
	@DisplayName("a locked resource is not a routine error: the caller may want to retry")
	void locked_resource() throws SQLException {
		try (Connection owner = DATA_SOURCE.getConnection(); Statement statement = owner.createStatement()) {
			owner.setAutoCommit(false);
			statement.execute("LOCK TABLE FIXTURE_ERROR_ROW IN EXCLUSIVE MODE");
			try {
				assertThatThrownBy(() -> errors.triggerError("NOWAIT"))
						.isExactlyInstanceOf(PlsqlExecutionException.class)
						.satisfies(e -> assertThat(((PlsqlExecutionException) e).getCode()).isEqualTo(54));
			} finally {
				owner.rollback();
				owner.setAutoCommit(true);
			}
		}
	}

	@Test
	@DisplayName("the driver rejecting the value is not a routine error: the routine did not even run")
	void driver_rejects() {
		assertThatThrownBy(() -> errors.typeTheDriverRefuses(java.net.URI.create("urn:x")))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.satisfies(e -> assertThat(((PlsqlExecutionException) e).getCode()).isEqualTo(17004));
	}

	@Test
	@DisplayName("the error of a cursor query only shows up when reading, and is still a routine error")
	void error_while_reading_cursor() {
		AtomicReference<PlsqlFailure> fails = new AtomicReference<>();
		Errors withTranslator = PlsqlPackages.create(Errors.class, DATA_SOURCE, f -> {
			fails.set(f);
			return new IllegalStateException();
		});

		assertThatThrownBy(withTranslator::cursorWithError).isInstanceOf(IllegalStateException.class);

		assertThat(fails.get().phase()).isEqualTo(PlsqlFailure.Phase.RESULT);
		assertThat(fails.get().cause().getErrorCode()).isEqualTo(1476);
		assertThatThrownBy(errors::cursorWithError)
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1476));
	}

	@Test
	@DisplayName("a custom translator decides everything, including what would be a routine error")
	void custom_translator() {
		AtomicReference<PlsqlFailure> fails = new AtomicReference<>();
		Errors withTranslator = PlsqlPackages.create(Errors.class, DATA_SOURCE, f -> {
			fails.set(f);
			return new IllegalArgumentException("rule " + f.cause().getErrorCode());
		});

		assertThatThrownBy(() -> withTranslator.triggerError("RAE"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("rule 20200");
		assertThat(fails.get().phase()).isEqualTo(PlsqlFailure.Phase.EXECUTION);
		assertThat(fails.get().raisedByRoutine()).isTrue();
	}

	@Test
	@DisplayName("an ORA-00942 while reading the data dictionary is not a routine error: the routine did not even run")
	void data_dictionary_failure() {
		SQLException withoutAccess = new SQLException("ORA-00942: table or view does not exist", "42000", 942);

		assertThatThrownBy(() -> PlsqlPackages.create(Errors.class, failingAt("prepareStatement", withoutAccess))
				.triggerError("RAE"))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.satisfies(e -> assertThat(((PlsqlExecutionException) e).getCode()).isEqualTo(942));
	}

	@Test
	@DisplayName("an error while preparing the call is not a routine error, even with a code in the range")
	void failure_while_preparing() {
		SQLException fails = new SQLException("ORA-20200: should not happen", "72000", 20200);

		assertThatThrownBy(() -> PlsqlPackages.create(Errors.class, failingAt("prepareCall", fails)).triggerError("RAE"))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.satisfies(e -> assertThat(((PlsqlExecutionException) e).getCode()).isEqualTo(20200));
	}

	/** A real {@code DataSource}, in which one connection method fails. */
	private static DataSource failingAt(String method, SQLException fails) {
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (dataSource, call, args) -> {
					Object result = invoke(DATA_SOURCE, call, args);
					if (!(result instanceof Connection connection)) {
						return result;
					}
					return Proxy.newProxyInstance(Connection.class.getClassLoader(),
							new Class<?>[] { Connection.class }, (proxy, connectionMethod, arguments) -> {
								if (connectionMethod.getName().equals(method)) {
									throw fails;
								}
								return invoke(connection, connectionMethod, arguments);
							});
				});
	}

	private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}
}
