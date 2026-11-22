package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The range of Oracle's {@code DATE} and {@code TIMESTAMP}: from January 1, 4712 BC, which is {@code java.time}'s
 * {@code -4711-01-01}, to December 31, 9999. Measured on 23ai and 19c: {@code DATE '-4712-01-01'} is Julian day 1,
 * and {@code DATE '-4713-01-01'} and {@code DATE '9999-12-31' + 1} fail with {@code ORA-01841}. A value beyond it
 * is refused, since the library builds a {@code DATE}'s bytes itself, and Oracle stores whatever bytes come.
 */
class OracleDatetimeLimitsTest {

	private static final String BEYOND = "is beyond the range of Oracle's dates, from January 1, 4712 BC (java.time's"
			+ " -4711-01-01) to December 31, 9999";

	@Test
	@DisplayName("the first and the last day are within, at any time of the day")
	void edges_are_within() {
		assertThatCode(() -> OracleDatetimeLimits.requireWithin(LocalDate.of(-4711, 1, 1))).doesNotThrowAnyException();
		assertThatCode(() -> OracleDatetimeLimits.requireWithin(LocalDate.of(9999, 12, 31))).doesNotThrowAnyException();
		assertThatCode(() -> OracleDatetimeLimits.requireWithin(LocalDateTime.of(-4711, 1, 1, 0, 0)))
				.doesNotThrowAnyException();
		assertThatCode(() -> OracleDatetimeLimits.requireWithin(LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999)))
				.doesNotThrowAnyException();
		assertThatCode(() -> OracleDatetimeLimits.requireWithin(OracleDatetimeLimits.MIN)).doesNotThrowAnyException();
		assertThatCode(() -> OracleDatetimeLimits.requireWithin(OracleDatetimeLimits.MAX)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("the day before the first and the day after the last are refused, naming the value and the range")
	void beyond_the_edges() {
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(LocalDate.of(-4712, 12, 31)))
				.isInstanceOf(ScalarCodec.ConversionException.class)
				.hasMessage("the value -4712-12-31 " + BEYOND + ": Oracle would store it as an invalid date, or as another"
						+ " date");
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(LocalDate.of(10000, 1, 1)))
				.isInstanceOf(ScalarCodec.ConversionException.class).hasMessageContaining("+10000-01-01 " + BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(LocalDateTime.of(10000, 1, 1, 0, 0)))
				.hasMessageContaining("+10000-01-01T00:00 " + BEYOND);
	}

	@Test
	@DisplayName("far beyond: a year whose century wraps around a byte into a valid, wrong date, and LocalDate's own"
			+ " limits")
	void far_beyond() {
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(LocalDate.of(25620, 6, 15))).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(LocalDate.of(-30000, 1, 1))).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(LocalDate.MAX)).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(LocalDate.MIN)).hasMessageContaining(BEYOND);
	}

	@Test
	@DisplayName("a value with a time zone: its local fields and its instant in UTC, which Oracle stores, both within")
	void with_a_time_zone() {
		assertThatCode(() -> OracleDatetimeLimits.requireWithin(OffsetDateTime.of(9999, 12, 31, 20, 0, 0, 0,
				ZoneOffset.ofHours(-3)))).doesNotThrowAnyException();
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(OffsetDateTime.of(9999, 12, 31, 23, 0, 0, 0,
				ZoneOffset.ofHours(-3))))
				.as("the local day is within, its instant in UTC is not")
				.hasMessageContaining("the value 9999-12-31T23:00-03:00, which is +10000-01-01T02:00 in UTC, " + BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(OffsetDateTime.of(10000, 1, 1, 1, 0, 0, 0,
				ZoneOffset.ofHours(5))))
				.as("its instant in UTC is within, its local day is not").hasMessageContaining(BEYOND);
		// Paris was then on its local mean time, +00:09:21: 00:05 there is the day before in UTC
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(ZonedDateTime.of(-4711, 1, 1, 0, 5, 0, 0,
				ZoneId.of("Europe/Paris"))))
				.hasMessageContaining("which is -4712-12-31T23:55:39 in UTC, " + BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(LocalDateTime.of(10000, 1, 1, 0, 0)
				.toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC))).hasMessageContaining(BEYOND);
	}

	@Test
	@DisplayName("java.util.Date and java.sql.Timestamp, by the fields the driver writes: the JVM's time zone and"
			+ " calendar")
	void legacy_types() {
		Timestamp beyond = Timestamp.valueOf(LocalDateTime.of(10000, 1, 1, 12, 0));
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(beyond)).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithin(new java.util.Date(beyond.getTime())))
				.hasMessageContaining(BEYOND);
		assertThatCode(() -> OracleDatetimeLimits.requireWithin(Timestamp.valueOf(LocalDateTime.of(2026, 11, 22, 10, 0))))
				.doesNotThrowAnyException();
		assertThatCode(() -> OracleDatetimeLimits.requireWithin(java.sql.Date.valueOf(LocalDate.of(9999, 12, 31))))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("for a value of any type, a date is checked by its type")
	void dispatch() {
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithinIfDate(LocalDate.of(10000, 1, 1)))
				.hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithinIfDate(LocalDateTime.of(10000, 1, 1, 0, 0)))
				.hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithinIfDate(OffsetDateTime.of(10000, 1, 1, 0, 0, 0, 0,
				ZoneOffset.UTC))).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithinIfDate(ZonedDateTime.of(10000, 1, 1, 0, 0, 0, 0,
				ZoneOffset.UTC))).hasMessageContaining(BEYOND);
		assertThatThrownBy(() -> OracleDatetimeLimits.requireWithinIfDate(Timestamp.valueOf(LocalDateTime.of(10000, 1, 1,
				0, 0)))).hasMessageContaining(BEYOND);
	}

	@Test
	@DisplayName("for a value of any type: anything else than a date, and null, is not a date, and passes")
	void not_a_date() {
		assertThatCode(() -> OracleDatetimeLimits.requireWithinIfDate(null)).doesNotThrowAnyException();
		assertThatCode(() -> OracleDatetimeLimits.requireWithinIfDate("10000-01-01")).doesNotThrowAnyException();
		assertThatCode(() -> OracleDatetimeLimits.requireWithinIfDate(Instant.MAX))
				.as("an Instant reaches the funnel as an OffsetDateTime").doesNotThrowAnyException();
	}
}
