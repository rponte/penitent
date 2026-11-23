package io.github.rponte.penitent.mock;

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

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * A stub's numbers are written as {@code NUMBER} literals, so a value beyond {@code NUMBER}'s range, which the core
 * refuses before a call, is refused in a stub too, with the same message (#141).
 */
class StubNumberLimitsTest {

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		BigDecimal calculate(BigDecimal pWeight);
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a stub's number at NUMBER's edges is returned and matched")
	void edges() {
		BigDecimal largest = new BigDecimal("9." + "9".repeat(39) + "E+125");
		when(mocks.mock(Freights.class).calculate(new BigDecimal("1E-130"))).thenReturn(largest);

		assertThat(PlsqlPackages.create(Freights.class, dataSource).calculate(new BigDecimal("1E-130")))
				.isEqualByComparingTo(largest);
	}

	@Test
	@DisplayName("a stub's number beyond NUMBER's range, returned or matched, is refused with the core's message")
	void beyond() {
		Freights mock = mocks.mock(Freights.class);
		assertThatThrownBy(() -> when(mock.calculate(BigDecimal.ONE)).thenReturn(new BigDecimal("1E+200")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the value 1E+200 is beyond the range of Oracle's NUMBER");
		assertThatThrownBy(() -> when(mock.calculate(new BigDecimal("1E-131"))).thenReturn(BigDecimal.ONE))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the value 1E-131 is beyond the range of Oracle's NUMBER");
	}
}
