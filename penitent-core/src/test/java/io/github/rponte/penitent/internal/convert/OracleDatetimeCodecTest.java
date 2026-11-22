package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import oracle.sql.DATE;
import oracle.sql.TIMESTAMP;

/**
 * The bytes of an Oracle {@code DATE} or {@code TIMESTAMP}, built by the library from the fields of a
 * {@code LocalDateTime} or {@code LocalDate}, without the JVM's time zone, so that the result does not
 * depend on the driver version, and read back the same way. Driver 23 already does both this way, and is the
 * reference.
 */
class OracleDatetimeCodecTest {

	private static int[] unsigned(byte[] bytes) {
		int[] values = new int[bytes.length];
		for (int i = 0; i < bytes.length; i++) {
			values[i] = bytes[i] & 0xFF;
		}
		return values;
	}

	@Test
	@DisplayName("the fields, one per byte, plus 100 for the century and the year, plus 1 for the time")
	void fields() {
		assertThat(unsigned(OracleDatetimeCodec.encodeTimestamp(LocalDateTime.of(2025, 3, 9, 2, 30, 15)).toBytes()))
				.containsExactly(120, 125, 3, 9, 3, 31, 16);
		assertThat(unsigned(OracleDatetimeCodec.encodeDate(LocalDate.of(2025, 3, 9)).toBytes()))
				.containsExactly(120, 125, 3, 9, 1, 1, 1);
	}

	@Test
	@DisplayName("a fraction of a second adds the nanoseconds in four bytes, big-endian")
	void nanoseconds() {
		assertThat(unsigned(OracleDatetimeCodec.encodeTimestamp(LocalDateTime.of(2025, 3, 9, 2, 30, 15, 123456789)).toBytes()))
				.containsExactly(120, 125, 3, 9, 3, 31, 16, 7, 91, 205, 21);
	}

	@Test
	@DisplayName("a year before 1: java.time's 0 is 1 BC, stored as 100 minus the century and the year of the BC year")
	void before_christ() {
		assertThat(unsigned(OracleDatetimeCodec.encodeDate(LocalDate.of(0, 12, 31)).toBytes()))
				.containsExactly(100, 99, 12, 31, 1, 1, 1);
		// Oracle's first day, January 1, 4712 BC, Julian day 1
		assertThat(unsigned(OracleDatetimeCodec.encodeDate(LocalDate.of(-4711, 1, 1)).toBytes()))
				.containsExactly(53, 88, 1, 1, 1, 1, 1);
	}

	@Test
	@DisplayName("beyond Oracle's range, no bytes are built: the century, one byte, would wrap around into another date")
	void beyond_the_range() {
		// 25620 would become 100 + 256 = 356, which wraps to 100: the year 20 AD, a valid and wrong date
		assertThatThrownBy(() -> OracleDatetimeCodec.encodeDate(LocalDate.of(25620, 6, 15)))
				.isInstanceOf(ScalarCodec.ConversionException.class)
				.hasMessageContaining("is beyond the range of Oracle's dates");
		assertThatThrownBy(() -> OracleDatetimeCodec.encodeTimestamp(LocalDateTime.of(10000, 1, 1, 0, 0)))
				.hasMessageContaining("is beyond the range of Oracle's dates");
		assertThatThrownBy(() -> OracleDatetimeCodec.encodeDate(LocalDate.of(-4712, 12, 31)))
				.hasMessageContaining("is beyond the range of Oracle's dates");
	}

	@Test
	@DisplayName("a day the JVM's time zone skips keeps its fields: no time zone is involved")
	void daylight_saving_gap() {
		LocalDateTime gap = LocalDateTime.of(2025, 3, 9, 2, 30, 15);
		TimeZone original = TimeZone.getDefault();
		TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
		try {
			assertThat(unsigned(OracleDatetimeCodec.encodeTimestamp(gap).toBytes())).containsExactly(120, 125, 3, 9, 3, 31, 16);
			assertThat(OracleDatetimeCodec.decode(OracleDatetimeCodec.encodeTimestamp(gap).toBytes())).isEqualTo(gap);
		} finally {
			TimeZone.setDefault(original);
		}
	}

	@Test
	@DisplayName("reading: the fields straight from the bytes, a gap of the JVM's time zone included")
	void reading() {
		assertThat(OracleDatetimeCodec.decode(new byte[] { 120, 125, 3, 9, 3, 31, 16 }))
				.isEqualTo(LocalDateTime.of(2025, 3, 9, 2, 30, 15));
		assertThat(OracleDatetimeCodec.decode(new byte[] { 120, 125, 3, 9, 3, 31, 16, 7, 91, (byte) 205, 21 }))
				.isEqualTo(LocalDateTime.of(2025, 3, 9, 2, 30, 15, 123456789));
		// reading is not checked against Oracle's range: these bytes, a year before Oracle's first day, come back as
		// they are, since a value already stored is the database's to hold
		assertThat(OracleDatetimeCodec.decode(new byte[] { 53, 87, 1, 1, 1, 1, 1 }))
				.isEqualTo(LocalDateTime.of(-4712, 1, 1, 0, 0));
	}

	@Test
	@DisplayName("writing and reading back gives the same value, on any driver, over Oracle's whole range")
	void round_trip() {
		List<String> differences = new ArrayList<>();
		for (int year = -4711; year <= 9999; year += 37) {
			for (int nano : new int[] { 0, 1, 123456789, 999999999 }) {
				LocalDateTime value = LocalDateTime.of(year, 1 + Math.floorMod(year, 12), 1 + Math.floorMod(year, 28),
						Math.floorMod(year, 24), Math.floorMod(year, 60), Math.floorMod(year * 7, 60), nano);
				LocalDateTime back = OracleDatetimeCodec.decode(OracleDatetimeCodec.encodeTimestamp(value).toBytes());
				if (!value.equals(back)) {
					differences.add(value + " came back as " + back);
				}
			}
		}
		assertThat(differences).isEmpty();
	}

	@Test
	@DisplayName("byte for byte the same as driver 23, over B.C., the calendar change of 1582, nanoseconds and every hour")
	void same_as_driver_23() {
		assumeTrue(Driver23.isTheDriver(), "driver 23 is the reference");

		List<String> differences = new ArrayList<>();
		// Oracle's range only: beyond it, the library refuses the value, and driver 23 builds invalid bytes (#142)
		int[] years = { -4711, -4710, -1000, -1, 0, 1, 99, 100, 999, 1000, 1582, 1600, 1900, 1969, 1970, 2000, 2024,
				2025, 2100, 9998, 9999 };
		int[] nanos = { 0, 1, 999, 1000000, 123456789, 999999999 };
		for (int year : years) {
			for (int month = 1; month <= 12; month += 11) {
				for (int hour = 0; hour < 24; hour++) {
					for (int nano : nanos) {
						LocalDateTime value = LocalDateTime.of(year, month, month == 2 ? 28 : 15, hour, hour * 2 + 1,
								59 - hour, nano);
						byte[] ours = OracleDatetimeCodec.encodeTimestamp(value).toBytes();
						byte[] driver = Driver23.timestamp(value);
						if (!java.util.Arrays.equals(ours, driver)) {
							differences.add(value + ": " + java.util.Arrays.toString(ours) + " != "
									+ java.util.Arrays.toString(driver));
						}
					}
				}
				LocalDate date = LocalDate.of(year, month, 15);
				if (!java.util.Arrays.equals(OracleDatetimeCodec.encodeDate(date).toBytes(), Driver23.date(date))) {
					differences.add("DATE " + date);
				}
			}
		}
		for (int year : years) {
			for (int nano : nanos) {
				byte[] bytes = Driver23.timestamp(LocalDateTime.of(year, 6, 15, 2, 30, 15, nano));
				if (!OracleDatetimeCodec.decode(bytes).equals(Driver23.localDateTime(bytes))) {
					differences.add("reading " + java.util.Arrays.toString(bytes));
				}
			}
		}
		LocalDate gregorianChange = LocalDate.of(1582, 10, 10);
		if (!java.util.Arrays.equals(OracleDatetimeCodec.encodeDate(gregorianChange).toBytes(), Driver23.date(gregorianChange))) {
			differences.add("DATE " + gregorianChange);
		}

		assertThat(differences).isEmpty();
	}
}
