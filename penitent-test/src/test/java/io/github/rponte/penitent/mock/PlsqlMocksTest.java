package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.isNull;
import static io.github.rponte.penitent.mock.PlsqlMocks.never;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.times;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * Mocks against a real Oracle: a stubbed routine answers as stubbed, in Java and in PL/SQL that calls it, while
 * everything else in its package, and everything outside the test's edition, stays real.
 */
class PlsqlMocksTest {

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		BigDecimal calculate(String pOrigin, String pDestination);

		BigDecimal calculate(BigDecimal pWeight);

		void quote(String pUf, PlsqlOut<BigDecimal> pValue);

		String label(LocalDate pDay);

		String realOne();
	}

	@PlsqlPackage("CHECKOUT")
	interface Checkout {
		BigDecimal total(String pOrigin, String pDestination);
	}

	@PlsqlPackage("WRAPPED_ONE")
	interface WrappedOne {
		BigDecimal secret();
	}

	private static DataSource pool;
	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Freights freights;
	private Checkout checkout;
	private Freights realFreights;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		dataSource = PlsqlMocks.wrap(pool);
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		freights = PlsqlPackages.create(Freights.class, dataSource);
		checkout = PlsqlPackages.create(Checkout.class, dataSource);
		realFreights = PlsqlPackages.create(Freights.class, pool);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a stubbed function returns the stubbed value")
	void returns() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

		assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
	}

	@Test
	@DisplayName("a stub can raise an application error, which arrives as a PlsqlRoutineRaisedException")
	void raises_application_error() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.calculate("SP", "XX")).thenThrow(-20200, "Cannot calculate freight to XX");

		assertThatThrownBy(() -> freights.calculate("SP", "XX"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(20200);
					assertThat(e.getReason()).isEqualTo("Cannot calculate freight to XX");
				});
	}

	@Test
	@DisplayName("a stub can raise a predefined exception, by name")
	void raises_predefined_exception() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.calculate("SP", "XX")).thenRaise("NO_DATA_FOUND");

		assertThatThrownBy(() -> freights.calculate("SP", "XX"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1403));
	}

	@Test
	@DisplayName("a call that no stub matches fails, naming the routine (strict)")
	void strict() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

		assertThatThrownBy(() -> freights.calculate("RJ", "BA"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(20999);
					assertThat(e.getReason()).contains("FREIGHTS.CALCULATE").contains("no stub matches");
				});
	}

	@Test
	@DisplayName("matchers: any, eq and isNull, with the last stub that matches winning, as in Mockito")
	void matchers() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculate(eq("SP"), any())).thenReturn(new BigDecimal("2"));
		when(mock.calculate(eq("SP"), isNull())).thenReturn(new BigDecimal("1"));

		assertThat(freights.calculate("SP", null)).isEqualByComparingTo("1");
		assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("2");
	}

	@Test
	@DisplayName("a call that mixes matchers and plain values is refused, as Mockito does")
	void mixed_matchers() {
		Freights mock = mocks.mock(Freights.class);

		assertThatThrownBy(() -> when(mock.calculate("SP", any())))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("eq(");
	}

	@Test
	@DisplayName("a procedure sets its stubbed OUT and returns")
	void out_parameter() {
		Freights mock = mocks.mock(Freights.class);

		when(() -> mock.quote(eq("CE"), out(new BigDecimal("99.99"))));

		PlsqlOut<BigDecimal> value = new PlsqlOut<>();
		freights.quote("CE", value);
		assertThat(value.get()).isEqualByComparingTo("99.99");
	}

	@Test
	@DisplayName("a date argument is matched by its value")
	void date_argument() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.label(LocalDate.of(2026, 11, 11))).thenReturn("stubbed");

		assertThat(freights.label(LocalDate.of(2026, 11, 11))).isEqualTo("stubbed");
	}

	@Test
	@DisplayName("everything else in the package stays real: the overload, the private function, the other routines")
	void the_rest_stays_real() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

		assertThat(freights.calculate(BigDecimal.TEN)).isEqualByComparingTo("20");
		assertThat(freights.realOne()).isEqualTo("REAL");
		PlsqlOut<BigDecimal> value = new PlsqlOut<>();
		freights.quote("CE", value);
		assertThat(value.get()).as("quote uses the private base_rate").isEqualByComparingTo("50");
	}

	@Test
	@DisplayName("PL/SQL that calls the stubbed routine sees the mock")
	void plsql_caller() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

		assertThat(checkout.total("SP", "CE")).isEqualByComparingTo("124.90");
	}

	@Test
	@DisplayName("outside the test's edition, the routine stays real at the same moment")
	void real_outside_the_edition() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

		assertThat(realFreights.calculate("SP", "CE")).isEqualByComparingTo("75");
	}

	@Test
	@DisplayName("verify counts the calls made in this test, from Java and from PL/SQL, by their arguments")
	void verifies() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculate(any(), any())).thenReturn(new BigDecimal("24.90"));

		freights.calculate("SP", "CE");
		checkout.total("SP", "CE");
		freights.calculate("SP", null);

		verify(mock, times(2)).calculate("SP", "CE");
		verify(mock).calculate(eq("SP"), isNull());
		verify(mock, times(3)).calculate(any(), any());
		verify(mock, never()).calculate("RJ", "BA");
		assertThatThrownBy(() -> verify(mock).calculate("SP", "CE"))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("FREIGHTS.CALCULATE")
				.hasMessageContaining("1 time")
				.hasMessageContaining("2 times");
	}

	@Test
	@DisplayName("a wrapped package body cannot be parsed, and is refused with a clear message")
	void wrapped_body() {
		WrappedOne mock = mocks.mock(WrappedOne.class);

		assertThatThrownBy(() -> when(mock.secret()).thenReturn(BigDecimal.ONE))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("WRAPPED_ONE")
				.hasMessageContaining("wrapped");
	}

	@Test
	@DisplayName("a stub can return null")
	void returns_null() {
		Freights mock = mocks.mock(Freights.class);

		when(mock.calculate("SP", "CE")).thenReturn(null);

		assertThat(freights.calculate("SP", "CE")).isNull();
	}
}
