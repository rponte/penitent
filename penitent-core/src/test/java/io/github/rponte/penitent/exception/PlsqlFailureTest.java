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

	private static PlsqlFailure during(Phase phase, int code) {
		return new PlsqlFailure("sql", new SQLException("ORA", "72000", code), phase);
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
