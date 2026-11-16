package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.anyInt;
import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.doRaise;
import static io.github.rponte.penitent.mock.PlsqlMocks.doReturn;
import static io.github.rponte.penitent.mock.PlsqlMocks.doThrow;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.times;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * The {@code doXxx()} family, as in Mockito: the answer first, then the call on the mock, so a stub of a procedure,
 * a {@code void} method, needs no lambda, and the package compiles once.
 */
class DoFamilyTest {

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		BigDecimal calculate(String pOrigin, String pDestination);

		void quote(String pUf, PlsqlOut<BigDecimal> pValue);

		void integrate(int pOperation, String pName);
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Freights freights;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		freights = PlsqlPackages.create(Freights.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("doNothing() stubs a procedure: it sets its OUT values, given with out(), and returns")
	void do_nothing() {
		Freights mock = mocks.mock(Freights.class);

		doNothing().when(mock).quote(eq("CE"), out(new BigDecimal("99.99")));

		PlsqlOut<BigDecimal> value = new PlsqlOut<>();
		freights.quote("CE", value);
		assertThat(value.get()).isEqualByComparingTo("99.99");
	}

	@Test
	@DisplayName("doThrow() raises an application error from a procedure, compiling the package once")
	void do_throw() {
		Logger log = (Logger) LoggerFactory.getLogger("io.github.rponte.penitent.mock");
		Level configured = log.getLevel();
		ListAppender<ILoggingEvent> stubs = new ListAppender<>();
		stubs.start();
		log.addAppender(stubs);
		log.setLevel(Level.DEBUG);
		try {
			doThrow(-20300, "not integrated").when(mocks.mock(Freights.class)).integrate(anyInt(), any());

			assertThatThrownBy(() -> freights.integrate(781, "VERBA"))
					.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
						assertThat(e.getCode()).isEqualTo(20300);
						assertThat(e.getReason()).isEqualTo("not integrated");
					});
			assertThat(stubs.list).filteredOn(e -> e.getFormattedMessage().startsWith("Stubbed routine"))
					.as("one compilation, not one for doing nothing and another for raising").hasSize(1);
		} finally {
			log.detachAppender(stubs);
			log.setLevel(configured);
		}
	}

	@Test
	@DisplayName("doRaise() raises one of Oracle's predefined exceptions, by name")
	void do_raise() {
		doRaise("NO_DATA_FOUND").when(mocks.mock(Freights.class)).integrate(anyInt(), any());

		assertThatThrownBy(() -> freights.integrate(1, "VERBA"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1403));
	}

	@Test
	@DisplayName("doReturn() stubs a function, as when(...).thenReturn(...) does, the last stub that matches winning")
	void do_return() {
		Freights mock = mocks.mock(Freights.class);

		doReturn(new BigDecimal("10.00")).when(mock).calculate(eq("SP"), any());
		doReturn(new BigDecimal("24.90")).when(mock).calculate("SP", "CE");

		assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
		assertThat(freights.calculate("SP", "RJ")).isEqualByComparingTo("10.00");
	}

	@Test
	@DisplayName("verify counts the calls of a routine stubbed with doXxx()")
	void verify_after_do() {
		Freights mock = mocks.mock(Freights.class);
		doNothing().when(mock).integrate(anyInt(), any());

		freights.integrate(781, "VERBA");
		freights.integrate(201, "VERBA");

		verify(mock, times(2)).integrate(anyInt(), eq("VERBA"));
	}

	@Test
	@DisplayName("doNothing() on a function and doReturn() on a procedure are refused, saying what to use")
	void wrong_kind() {
		Freights mock = mocks.mock(Freights.class);

		assertThatThrownBy(() -> doNothing().when(mock).calculate("SP", "CE"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("calculate")
				.hasMessageContaining("doReturn");
		assertThatThrownBy(() -> doReturn(BigDecimal.ONE).when(mock).integrate(1, "VERBA"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("integrate")
				.hasMessageContaining("doNothing");
	}

	@Test
	@DisplayName("doXxx().when(mock) with no call after it is refused, at the next doXxx(), when or verify, or at end()")
	void no_call_after_it() {
		Freights mock = mocks.mock(Freights.class);

		doNothing().when(mock);
		assertThatThrownBy(() -> doReturn(BigDecimal.ONE).when(mock))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("doNothing().when(mock) was not followed by a call on the mock");

		doThrow(-20300, "never made").when(mock);
		assertThatThrownBy(() -> verify(mock))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("doThrow(-20300, ...).when(mock)");

		doNothing().when(mock);
		assertThatThrownBy(() -> mocks.end())
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("doNothing().when(mock)");
		assertThat(TestKit.editionsLeft(TestDatabase.pool())).as("the edition still goes").isZero();
		mocks.begin();
	}

	@Test
	@DisplayName("doXxx().when(...) of something that is not a mock is refused")
	void not_a_mock() {
		assertThatThrownBy(() -> doNothing().when(freights))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("PlsqlMocks.mock");
	}
}
