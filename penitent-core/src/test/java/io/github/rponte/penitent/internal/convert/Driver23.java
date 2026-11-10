package io.github.rponte.penitent.internal.convert;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZonedDateTime;

import oracle.jdbc.OracleDriver;
import oracle.sql.DATE;
import oracle.sql.INTERVALDS;
import oracle.sql.INTERVALYM;
import oracle.sql.TIMESTAMP;
import oracle.sql.TIMESTAMPTZ;

/**
 * Driver 23's own conversions between {@code java.time} and the bytes of Oracle's types: the reference that the
 * library's codecs are compared with, byte for byte, in the tests named {@code same_as_driver_23}.
 *
 * <p>They are called by reflection because older drivers do not have them: driver 21 has no {@code java.time}
 * methods for intervals, and driver 19 none for dates either. The tests that use them have to compile on every
 * driver the build runs with, and skip themselves, with {@link #isTheDriver()}, on any other driver.
 */
final class Driver23 {

	private Driver23() {
	}

	/** Whether the driver on the classpath is 23 or later, the only one these methods can be called on. */
	static boolean isTheDriver() {
		return new OracleDriver().getMajorVersion() >= 23;
	}

	static byte[] timestamp(LocalDateTime value) {
		return ((TIMESTAMP) call(TIMESTAMP.class, null, "of", LocalDateTime.class, value)).toBytes();
	}

	static byte[] date(LocalDate value) {
		return ((DATE) call(DATE.class, null, "of", LocalDate.class, value)).toBytes();
	}

	static LocalDateTime localDateTime(byte[] timestamp) {
		return (LocalDateTime) call(TIMESTAMP.class, new TIMESTAMP(timestamp), "toLocalDateTime", null, null);
	}

	static byte[] timestampTz(ZonedDateTime value) {
		return ((TIMESTAMPTZ) call(TIMESTAMPTZ.class, null, "of", ZonedDateTime.class, value)).toBytes();
	}

	static ZonedDateTime zonedDateTime(byte[] timestampTz) {
		return (ZonedDateTime) call(TIMESTAMPTZ.class, null, "toZonedDateTime", byte[].class, timestampTz);
	}

	static byte[] intervalDs(Duration value) {
		return ((INTERVALDS) call(INTERVALDS.class, null, "toIntervalds", Duration.class, value)).toBytes();
	}

	static Duration duration(byte[] intervalDs) {
		return (Duration) call(INTERVALDS.class, new INTERVALDS(intervalDs), "getDuration", null, null);
	}

	static byte[] intervalYm(Period value) {
		return ((INTERVALYM) call(INTERVALYM.class, null, "toIntervalym", Period.class, value)).toBytes();
	}

	static Period period(byte[] intervalYm) {
		return (Period) call(INTERVALYM.class, new INTERVALYM(intervalYm), "getPeriod", null, null);
	}

	private static Object call(Class<?> type, Object target, String method, Class<?> parameter, Object argument) {
		try {
			return parameter == null ? type.getMethod(method).invoke(target)
					: type.getMethod(method, parameter).invoke(target, argument);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("driver 23's " + type.getSimpleName() + "." + method
					+ " failed, or this is not driver 23: " + e.getMessage(), e);
		}
	}
}
