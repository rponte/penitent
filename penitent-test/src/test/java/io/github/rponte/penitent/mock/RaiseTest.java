package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.doRaise;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static io.github.rponte.penitent.mock.PredefinedError.DUP_VAL_ON_INDEX;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

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
 * The exception a stub raises, as a type: one of Oracle's predefined exceptions, from {@link PredefinedError}, or one a
 * package declares in its spec, from {@link PlsqlError#declared}, which its callers catch by name.
 */
class RaiseTest {

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		BigDecimal calculate(String pOrigin, String pDestination);

		void quote(String pUf, PlsqlOut<BigDecimal> pValue);
	}

	@PlsqlPackage("SAFE_CHECKOUT")
	interface Checkout {
		BigDecimal total(String pOrigin, String pDestination);
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Freights freights;
	private Checkout checkout;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		freights = PlsqlPackages.create(Freights.class, dataSource);
		checkout = PlsqlPackages.create(Checkout.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("every predefined exception of STANDARD is raised, and reaches Java with its Oracle code, in whichever"
			+ " exception the core translates it to")
	void every_predefined_exception() {
		Freights mock = mocks.mock(Freights.class);
		for (PredefinedError error : PredefinedError.values()) {
			when(mock.calculate("SP", error.name())).thenRaise(error);
		}

		for (PredefinedError error : PredefinedError.values()) {
			assertThat(oracleCodeOf(() -> freights.calculate("SP", error.name()))).as(error.name())
					.isEqualTo(error.code());
		}
	}

	@Test
	@DisplayName("doRaise() raises a predefined exception from a procedure")
	void do_raise_from_a_procedure() {
		doRaise(DUP_VAL_ON_INDEX).when(mocks.mock(Freights.class)).quote(any(), any());

		assertThatThrownBy(() -> freights.quote("CE", new PlsqlOut<>()))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1));
	}

	@Test
	@DisplayName("an exception the mocked package declares is caught by name by the PL/SQL that calls the routine")
	void declared_in_the_mocked_package() {
		when(mocks.mock(Freights.class).calculate("SP", "XX")).thenRaise(PlsqlError.declared("FREIGHTS", "UNKNOWN_UF"));

		assertThat(checkout.total("SP", "XX")).isEqualByComparingTo("-1");
	}

	@Test
	@DisplayName("an exception another package declares is caught by name too")
	void declared_in_another_package() {
		when(mocks.mock(Freights.class).calculate("SP", "XX"))
				.thenRaise(PlsqlError.declared("freight_errors", "no_quote"));

		assertThat(checkout.total("SP", "XX")).isEqualByComparingTo("-2");
	}

	@Test
	@DisplayName("a declared exception no one catches reaches Java as an unhandled user-defined exception")
	void declared_uncaught() {
		when(mocks.mock(Freights.class).calculate("SP", "XX")).thenRaise(PlsqlError.declared("FREIGHTS", "UNKNOWN_UF"));

		assertThatThrownBy(() -> freights.calculate("SP", "XX"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(6510));
	}

	@Test
	@DisplayName("a declared exception that does not exist is reported, naming it as the likely mistake")
	void declared_not_found() {
		assertThatThrownBy(() -> when(mocks.mock(Freights.class).calculate("SP", "XX"))
				.thenRaise(PlsqlError.declared("FREIGHTS", "NO_SUCH_ERROR")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("FREIGHTS.NO_SUCH_ERROR")
				.hasMessageContaining("spec");
	}

	/** The Oracle error number of what {@code call} throws: the first SQLException in its causes. */
	private static int oracleCodeOf(Runnable call) {
		try {
			call.run();
		} catch (RuntimeException e) {
			for (Throwable cause = e; cause != null; cause = cause.getCause()) {
				if (cause instanceof java.sql.SQLException sql) {
					return sql.getErrorCode();
				}
			}
			throw new AssertionError("no SQLException behind " + e, e);
		}
		throw new AssertionError("nothing was raised");
	}

	@Test
	@DisplayName("a package or exception name that is not an identifier is refused before compiling")
	void not_identifiers() {
		assertThatThrownBy(() -> PlsqlError.declared("FREIGHTS", "no quote"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("no quote");
		assertThatThrownBy(() -> PlsqlError.declared("", "NO_QUOTE"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
