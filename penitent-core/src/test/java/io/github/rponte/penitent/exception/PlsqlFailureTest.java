package io.github.rponte.penitent.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.rponte.penitent.exception.PlsqlFailure.Phase;

class PlsqlFailureTest {

	/** An error as the database sends it: the driver keeps Oracle's own report as the cause, on every version. */
	private static PlsqlFailure during(Phase phase, int code) {
		return new PlsqlFailure("sql", fromTheDatabase(code), phase);
	}

	private static SQLException fromTheDatabase(int code) {
		return new SQLException("ORA-" + code, "72000", code,
				new oracle.jdbc.OracleDatabaseException(0, code, "ORA-" + code, "sql", "sql"));
	}

	@ParameterizedTest
	@ValueSource(ints = { 20000, 20200, 20999, 21000, 1, 1403, 1422, 1476, 1438, 1722, 1800, 1841, 1899, 2290,
			2299, 6502, 6510, 6511, 6530, 6531, 6532, 6533, 6592, 900, 942, 12899, 30625 })
	@DisplayName("during execution, these codes came from the routine")
	void came_from_the_routine(int code) {
		assertThat(during(Phase.EXECUTION, code).raisedByRoutine()).isTrue();
	}

	@ParameterizedTest
	@ValueSource(ints = {
			// the routine did not run
			6550, 6508, 4060, 4063, 4068, 4069,
			// system
			370, 429, 600, 899, 6430, 6449, 7200, 7445, 7999, 9700, 9999,
			// server and background processes
			100, 120, 440, 569,
			// resources and concurrency
			18, 28, 35, 50, 51, 54, 60, 68, 2376, 2399, 4020, 4031, 4039, 8177, 30006,
			// connection, session and driver
			1012, 1013, 1017, 2700, 2899, 3100, 3113, 3199, 6000, 6149, 6250, 6429, 6600, 6999, 12100, 12170, 12299,
			12500, 12541, 12599, 17000, 17004, 17008, 17041, 17999,
			// XA, two-task and SQL*Connect
			150, 159, 570, 599, 6200, 6249, 7000, 7199,
			// debug
			10000, 10999,
			// files, redo and licensing
			200, 369, 430, 439, 1100, 1250,
			// PL/SQL internals
			6500, 6501 })
	@DisplayName("even during execution, these codes belong to the infrastructure or to the call itself")
	void did_not_come_from_the_routine(int code) {
		assertThat(during(Phase.EXECUTION, code).raisedByRoutine()).isFalse();
	}

	@ParameterizedTest
	@EnumSource(value = Phase.class, names = { "EXECUTION", "RESULT" }, mode = EnumSource.Mode.EXCLUDE)
	@DisplayName("before execution, no code came from the routine")
	void before_execution(Phase phase) {
		assertThat(during(phase, 20200).raisedByRoutine()).isFalse();
		assertThat(during(phase, 942).raisedByRoutine()).isFalse();
	}

	@Test
	@DisplayName("when reading the result, the same list as execution applies")
	void when_reading_the_result() {
		assertThat(during(Phase.RESULT, 1476).raisedByRoutine()).isTrue();
		assertThat(during(Phase.RESULT, 17008).raisedByRoutine()).isFalse();
		assertThat(during(Phase.RESULT, 3113).raisedByRoutine()).isFalse();
	}

	@ParameterizedTest
	@ValueSource(ints = { 17001, 17004, 18722, 20001, 1403 })
	@DisplayName("an error the driver raises itself, in the client, never came from the routine, whatever its code:"
			+ " only an error the database sent carries Oracle's report, an OracleDatabaseException, as its cause")
	void not_from_the_database(int code) {
		PlsqlFailure failure = new PlsqlFailure("sql", new SQLException("client", "99999", code), Phase.EXECUTION);
		assertThat(failure.raisedByRoutine()).isFalse();
	}

	@Test
	@DisplayName("a failure of the driver itself, which the library wraps with code 0, never came from the routine")
	void failure_of_the_driver() {
		SQLException wrapped = new SQLException("the Oracle driver failed an internal check (oacdty: 0)",
				new AssertionError("oacdty: 0"));
		assertThat(new PlsqlFailure("sql", wrapped, Phase.EXECUTION).raisedByRoutine()).isFalse();
	}

	@Test
	@DisplayName("a database error the library wraps to explain it keeps its origin, anywhere in the chain of causes")
	void wrapped_database_error() {
		SQLException explained = new SQLException("explained", "72000", 20001, fromTheDatabase(20001));
		assertThat(new PlsqlFailure("sql", explained, Phase.EXECUTION).raisedByRoutine()).isTrue();
	}

	@Test
	@DisplayName("the default translator separates the two exceptions, and both keep the code")
	void default_translator() {
		PlsqlExceptionTranslator translator = new DefaultPlsqlExceptionTranslator();

		assertThat(translator.translate(during(Phase.EXECUTION, 1403)))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1403));
		assertThat(translator.translate(during(Phase.EXECUTION, 60)))
				.isExactlyInstanceOf(PlsqlExecutionException.class)
				.satisfies(e -> assertThat(((PlsqlExecutionException) e).getCode()).isEqualTo(60));
	}
}
