package io.github.rponte.penitent.internal.convert;

import java.time.Duration;
import java.time.Period;

import oracle.sql.INTERVALDS;
import oracle.sql.INTERVALYM;

/**
 * An Oracle {@code INTERVAL DAY TO SECOND} or {@code INTERVAL YEAR TO MONTH} built by the library from
 * a {@link Duration} or a {@link Period}, and read back into them.
 *
 * <p><b>Why the library does it, and not the driver.</b> {@code INTERVALDS.toIntervalds(Duration)},
 * {@code getDuration()}, {@code INTERVALYM.toIntervalym(Period)} and {@code getPeriod()} only exist from
 * driver 23 on; before it, the two types have nothing but a constructor from bytes and one from text.
 * The bytes are the same on every version, and {@code OracleIntervalCodecTest} compares them with driver
 * 23's, in both directions. Text would depend on parsing rules that the driver does not document.
 *
 * <p><b>Where it differs from driver 23.</b> For a negative {@code Duration} of whole seconds, such as
 * -1 second, driver 23 writes one second less and -1,000,000,000 nanoseconds, outside the range of the
 * type. Arithmetic in the database happens to normalize it, but {@code to_char} and {@code extract} read
 * it as written: -1 second became {@code -0 00:00:00}. Here every field takes the sign and the
 * nanoseconds stay in range, which {@code OracleTypesTest} checks in the database.
 *
 * <p><b>The format</b> is Oracle's internal representation. {@code INTERVAL DAY TO SECOND}: the days
 * in four bytes, big-endian, plus 2<sup>31</sup>; hours, minutes and seconds plus 60, one byte each;
 * the nanoseconds in four bytes plus 2<sup>31</sup>. {@code INTERVAL YEAR TO MONTH}: the years in four
 * bytes plus 2<sup>31</sup>, and the months plus 60. In a negative {@code Duration}, every field takes
 * the sign, the nanoseconds included. A {@code Period} goes as it is, as in driver 23: 14 months stay 14
 * months, without becoming a year and 2 months. As there, the range is left to the database, which
 * refuses what its precision does not hold.
 */
public final class OracleIntervalCodec {

	private static final int SECONDS_PER_DAY = 86_400;

	private OracleIntervalCodec() {
	}

	public static INTERVALDS encode(Duration value) {
		Duration magnitude = value.abs();
		long seconds = magnitude.getSeconds();
		long days = seconds / SECONDS_PER_DAY;
		if (days > Integer.MAX_VALUE) {
			throw new ScalarCodec.ConversionException("Duration " + value
					+ " has more days than INTERVAL DAY TO SECOND stores");
		}
		int sign = value.isNegative() ? -1 : 1;
		byte[] bytes = new byte[11];
		putInt(bytes, 0, sign * (int) days);
		bytes[4] = (byte) (sign * (seconds % SECONDS_PER_DAY / 3600) + 60);
		bytes[5] = (byte) (sign * (seconds % 3600 / 60) + 60);
		bytes[6] = (byte) (sign * (seconds % 60) + 60);
		putInt(bytes, 7, sign * magnitude.getNano());
		return new INTERVALDS(bytes);
	}

	/**
	 * Refuses the days of a {@code Period}, which {@code INTERVAL YEAR TO MONTH} cannot store: driver 23
	 * refuses them too, but with an {@code IllegalArgumentException} that does not name the type.
	 */
	public static INTERVALYM encode(Period value) {
		if (value.getDays() != 0) {
			throw new ScalarCodec.ConversionException("Period " + value
					+ " has days, which INTERVAL YEAR TO MONTH does not store");
		}
		byte[] bytes = new byte[5];
		putInt(bytes, 0, value.getYears());
		bytes[4] = (byte) (value.getMonths() + 60);
		return new INTERVALYM(bytes);
	}

	/** The inverse of {@link #encode(Duration)}. */
	public static Duration duration(byte[] bytes) {
		return Duration.ofDays(getInt(bytes, 0))
				.plusHours(bytes[4] - 60)
				.plusMinutes(bytes[5] - 60)
				.plusSeconds(bytes[6] - 60)
				.plusNanos(getInt(bytes, 7));
	}

	/** The inverse of {@link #encode(Period)}. */
	public static Period period(byte[] bytes) {
		return Period.of(getInt(bytes, 0), bytes[4] - 60, 0);
	}

	/** Plus 2<sup>31</sup>, which is flipping the sign bit. */
	private static void putInt(byte[] bytes, int offset, int value) {
		int stored = value ^ Integer.MIN_VALUE;
		bytes[offset] = (byte) (stored >>> 24);
		bytes[offset + 1] = (byte) (stored >>> 16);
		bytes[offset + 2] = (byte) (stored >>> 8);
		bytes[offset + 3] = (byte) stored;
	}

	private static int getInt(byte[] bytes, int offset) {
		int stored = (bytes[offset] & 0xFF) << 24 | (bytes[offset + 1] & 0xFF) << 16
				| (bytes[offset + 2] & 0xFF) << 8 | bytes[offset + 3] & 0xFF;
		return stored ^ Integer.MIN_VALUE;
	}
}
