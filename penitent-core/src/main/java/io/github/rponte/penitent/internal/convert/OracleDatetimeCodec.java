package io.github.rponte.penitent.internal.convert;

import java.time.LocalDate;
import java.time.LocalDateTime;

import oracle.sql.DATE;
import oracle.sql.TIMESTAMP;

/**
 * An Oracle {@code DATE} or {@code TIMESTAMP} built by the library from the fields of a
 * {@link LocalDateTime} or {@link LocalDate}, and read back into them, never through a time zone.
 * Of Oracle's datetime types, only these two: the ones with a time zone go through the driver, which
 * handles them the same way on every version the build tests.
 *
 * <p><b>Why the library does it, and not the driver.</b> {@code TIMESTAMP.of(LocalDateTime)} and
 * {@code DATE.of(LocalDate)} copy the fields from driver 23 on, but before it they go through the
 * JVM's time zone: in a zone with daylight saving time, 02:30 on the day the clocks jump was stored
 * as 03:30, and a day without midnight at 01:00, without any error. Reading back has the same
 * problem: {@code toLocalDateTime()} turns the stored 02:30 into 03:30. Building the bytes from the
 * fields, and the fields from the bytes, gives driver 23's result on every driver version. Reading a
 * scalar or a cursor column does not need it: there the library asks the driver for a
 * {@code LocalDateTime} directly, which copies the fields on the drivers the build tests; the driver's
 * own types only reach the library inside a composite or a collection. {@code OracleDatetimeCodecTest}
 * compares them with driver 23's, byte for byte, and {@code JvmTimeZoneTest} sends the hostile cases
 * through the database, on each driver the build runs with.
 *
 * <p><b>The format</b> is Oracle's internal representation of the two types: century + 100, year of
 * the century + 100, month, day, hour + 1, minute + 1 and second + 1, one per byte; a
 * {@code TIMESTAMP} with a fraction of a second adds the nanoseconds in four bytes, big-endian. A year
 * before 1 is a year B.C.: {@code java.time}'s 0 is 1 B.C., and Oracle stores 100 minus its century
 * and 100 minus its year of the century. As in driver 23, the calendar is not converted, so the days
 * of 1582 that the Gregorian change skipped keep their fields, and the range is not checked here: the
 * database refuses a year it cannot store.
 */
public final class OracleDatetimeCodec {

	private OracleDatetimeCodec() {
	}

	public static TIMESTAMP encodeTimestamp(LocalDateTime value) {
		int nanos = value.getNano();
		byte[] bytes = new byte[nanos == 0 ? 7 : 11];
		fill(bytes, value.toLocalDate(), value.getHour(), value.getMinute(), value.getSecond());
		if (nanos != 0) {
			bytes[7] = (byte) (nanos >>> 24);
			bytes[8] = (byte) (nanos >>> 16);
			bytes[9] = (byte) (nanos >>> 8);
			bytes[10] = (byte) nanos;
		}
		return new TIMESTAMP(bytes);
	}

	/** A {@code DATE} without a time: midnight, as {@code DATE.of(LocalDate)} stores it. */
	public static DATE encodeDate(LocalDate value) {
		byte[] bytes = new byte[7];
		fill(bytes, value, 0, 0, 0);
		return new DATE(bytes);
	}

	/**
	 * The fields of the bytes of a {@code DATE} or a {@code TIMESTAMP}, the inverse of {@link #encodeTimestamp}.
	 * Driver 21's {@code toLocalDateTime()} goes through the JVM's time zone on the way back too, and turns
	 * 02:30 on the day the clocks jump into 03:30; reading the bytes gives driver 23's result everywhere.
	 */
	public static LocalDateTime decode(byte[] bytes) {
		int century = (bytes[0] & 0xFF) - 100;
		int yearOfCentury = (bytes[1] & 0xFF) - 100;
		// Before Christ both are negative (100 minus the B.C. values), and the B.C. year n is java.time's 1 - n.
		int year = century >= 0 && yearOfCentury >= 0 ? century * 100 + yearOfCentury
				: 1 - (-century * 100 - yearOfCentury);
		int nanos = 0;
		if (bytes.length >= 11) {
			nanos = (bytes[7] & 0xFF) << 24 | (bytes[8] & 0xFF) << 16 | (bytes[9] & 0xFF) << 8 | bytes[10] & 0xFF;
		}
		return LocalDateTime.of(year, bytes[2], bytes[3], bytes[4] - 1, bytes[5] - 1, bytes[6] - 1, nanos);
	}

	private static void fill(byte[] bytes, LocalDate date, int hour, int minute, int second) {
		int year = date.getYear();
		if (year > 0) {
			bytes[0] = (byte) (year / 100 + 100);
			bytes[1] = (byte) (year % 100 + 100);
		} else {
			int beforeChrist = 1 - year;
			bytes[0] = (byte) (100 - beforeChrist / 100);
			bytes[1] = (byte) (100 - beforeChrist % 100);
		}
		bytes[2] = (byte) date.getMonthValue();
		bytes[3] = (byte) date.getDayOfMonth();
		bytes[4] = (byte) (hour + 1);
		bytes[5] = (byte) (minute + 1);
		bytes[6] = (byte) (second + 1);
	}
}
