package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * The bytes of an Oracle {@code INTERVAL}, built by the library from a {@code Duration} or a
 * {@code Period}, and read back into them, because drivers before 23 have no {@code java.time} methods
 * for intervals. Driver 23 has them, and is the reference.
 */
class OracleIntervalCodecTest {

	private static int[] unsigned(byte[] bytes) {
		int[] values = new int[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			values[i] = bytes[i] & 0xFF;
		}
		return values;
	}

	private static final Duration[] DURATIONS = { Duration.ZERO, Duration.ofDays(1).plusHours(2).plusMillis(345),
			Duration.ofSeconds(-1, 500_000_000), Duration.ofDays(-3).minusHours(4).minusNanos(1), Duration.ofNanos(1),
			Duration.ofNanos(-1), Duration.ofDays(999_999_999).plusHours(23).plusMinutes(59).plusSeconds(59)
					.plusNanos(999_999_999),
			Duration.ofDays(-999_999_999).minusSeconds(1), Duration.ofHours(-25).plusMinutes(3), Duration.ofSeconds(-1),
			Duration.ofMinutes(-1) };

	private static final Period[] PERIODS = { Period.ZERO, Period.of(2, 3, 0), Period.ofMonths(14),
			Period.of(-2, -3, 0), Period.of(1, -1, 0), Period.ofYears(999_999_999), Period.ofYears(-999_999_999) };

	@Test
	@DisplayName("INTERVAL DAY TO SECOND: the days and the nanoseconds in four bytes plus 2^31, the rest plus 60")
	void day_to_second() {
		assertThat(unsigned(OracleIntervalCodec.encode(Duration.ofDays(1).plusHours(2).plusMillis(345)).toBytes()))
				.containsExactly(128, 0, 0, 1, 62, 60, 60, 148, 144, 72, 64);
	}

	@Test
	@DisplayName("a negative Duration: every field takes the sign, the nanoseconds included")
	void negative_duration() {
		assertThat(unsigned(OracleIntervalCodec.encode(Duration.ofDays(-3).minusHours(4).minusNanos(1)).toBytes()))
				.containsExactly(127, 255, 255, 253, 56, 60, 60, 127, 255, 255, 255);
		assertThat(unsigned(OracleIntervalCodec.encode(Duration.ofSeconds(-1, 500_000_000)).toBytes()))
				.containsExactly(128, 0, 0, 0, 60, 60, 60, 98, 50, 155, 0);
	}

	@Test
	@DisplayName("a negative Duration of whole seconds: no fraction, unlike driver 23, which writes -1,000,000,000 nanoseconds")
	void negative_whole_seconds() {
		// Driver 23 gives [128, 0, 0, 0, 60, 60, 60, 68, 101, 54, 0] for -1 second: 0 seconds and -10^9
		// nanoseconds, outside the range of the type, which the database reads as -0 00:00:00. OracleTypesTest
		// shows what the database makes of each.
		assertThat(unsigned(OracleIntervalCodec.encode(Duration.ofSeconds(-1)).toBytes()))
				.containsExactly(128, 0, 0, 0, 60, 60, 59, 128, 0, 0, 0);
		assertThat(unsigned(OracleIntervalCodec.encode(Duration.ofHours(-25).plusMinutes(3)).toBytes()))
				.containsExactly(127, 255, 255, 255, 60, 3, 60, 128, 0, 0, 0);
	}

	@Test
	@DisplayName("INTERVAL YEAR TO MONTH: the years in four bytes plus 2^31, the months plus 60, as the Period has them")
	void year_to_month() {
		assertThat(unsigned(OracleIntervalCodec.encode(Period.of(2, 3, 0)).toBytes())).containsExactly(128, 0, 0, 2, 63);
		assertThat(unsigned(OracleIntervalCodec.encode(Period.of(-2, -3, 0)).toBytes()))
				.containsExactly(127, 255, 255, 254, 57);
		assertThat(unsigned(OracleIntervalCodec.encode(Period.ofMonths(14)).toBytes())).containsExactly(128, 0, 0, 0, 74);
	}

	@Test
	@DisplayName("a Period with days is refused: INTERVAL YEAR TO MONTH does not store them")
	void period_with_days() {
		assertThatThrownBy(() -> OracleIntervalCodec.encode(Period.of(1, 2, 3)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("Period P1Y2M3D has days, which INTERVAL YEAR TO MONTH does not store");
	}

	@Test
	@DisplayName("writing and reading back gives the same value, on any driver")
	void round_trip() {
		for (Duration duration : DURATIONS) {
			assertThat(OracleIntervalCodec.duration(OracleIntervalCodec.encode(duration).toBytes())).isEqualTo(duration);
		}
		for (Period period : PERIODS) {
			assertThat(OracleIntervalCodec.period(OracleIntervalCodec.encode(period).toBytes())).isEqualTo(period);
		}
	}

	@Test
	@DisplayName("byte for byte the same as driver 23, in both directions")
	void same_as_driver_23() {
		assumeTrue(Driver23.isTheDriver(), "driver 23 is the reference");

		List<String> differences = new ArrayList<>();
		for (Duration duration : DURATIONS) {
			byte[] driver = Driver23.intervalDs(duration);
			// Not a reference here: see negative_whole_seconds.
			boolean malformedByDriver23 = duration.isNegative() && duration.getNano() == 0;
			if (!malformedByDriver23 && !Arrays.equals(OracleIntervalCodec.encode(duration).toBytes(), driver)) {
				differences.add("writing " + duration);
			}
			if (!OracleIntervalCodec.duration(driver).equals(Driver23.duration(driver))) {
				differences.add("reading " + duration);
			}
		}
		for (Period period : PERIODS) {
			byte[] driver = Driver23.intervalYm(period);
			if (!Arrays.equals(OracleIntervalCodec.encode(period).toBytes(), driver)) {
				differences.add("writing " + period);
			}
			if (!OracleIntervalCodec.period(driver).equals(Driver23.period(driver))) {
				differences.add("reading " + period);
			}
		}

		assertThat(differences).isEmpty();
	}
}
