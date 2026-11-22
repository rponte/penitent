package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * A stub's dates are written as literals, which Oracle numbers without a year 0: {@code java.time}'s year 0 is 1 BC,
 * Oracle's {@code -0001}. So a BC date has to be written in Oracle's numbering to be the same day the core sends for
 * the same value, and a date beyond Oracle's range is refused, as the core refuses it (#142).
 */
class StubDatesTest {

	@PlsqlPackage("FREIGHT_SCALARS")
	interface Scalars {
		LocalDateTime shippedAt(BigDecimal pId);

		LocalDateTime dueOn(BigDecimal pId);

		@PlsqlRoutine("DUE_ON")
		LocalDate dueDay(BigDecimal pId);
	}

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		String label(LocalDate pDay);
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
	@DisplayName("a stub returns a BC date, from a LocalDate and from a LocalDateTime, as the same day")
	void before_christ_returned() {
		Scalars mock = mocks.mock(Scalars.class);
		Scalars scalars = PlsqlPackages.create(Scalars.class, dataSource);
		LocalDate oneBc = LocalDate.of(0, 6, 1);
		LocalDateTime twoBc = LocalDateTime.of(-1, 6, 1, 10, 30, 0, 123_456_000);
		when(mock.dueDay(BigDecimal.ONE)).thenReturn(oneBc);
		when(mock.shippedAt(BigDecimal.ONE)).thenReturn(twoBc);
		when(mock.dueDay(BigDecimal.TEN)).thenReturn(LocalDate.of(-4711, 1, 1));

		assertThat(scalars.dueDay(BigDecimal.ONE)).isEqualTo(oneBc);
		assertThat(scalars.shippedAt(BigDecimal.ONE)).isEqualTo(twoBc);
		assertThat(scalars.dueDay(BigDecimal.TEN)).as("Oracle's first day").isEqualTo(LocalDate.of(-4711, 1, 1));
	}

	@Test
	@DisplayName("a stub matches a BC date by value: the literal is the day the core binds")
	void before_christ_matched() {
		when(mocks.mock(Freights.class).label(LocalDate.of(-1, 6, 1))).thenReturn("two BC");

		assertThat(PlsqlPackages.create(Freights.class, dataSource).label(LocalDate.of(-1, 6, 1))).isEqualTo("two BC");
	}

	@Test
	@DisplayName("a stub's date beyond Oracle's range is refused, as the core refuses it")
	void beyond_the_range() {
		Scalars mock = mocks.mock(Scalars.class);
		assertThatThrownBy(() -> when(mock.dueDay(BigDecimal.ONE)).thenReturn(LocalDate.of(10000, 1, 1)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the value +10000-01-01 is beyond the range of Oracle's dates");
		assertThatThrownBy(() -> when(mock.shippedAt(BigDecimal.ONE)).thenReturn(LocalDateTime.of(-4712, 12, 31, 0, 0)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("is beyond the range of Oracle's dates");
	}
}
