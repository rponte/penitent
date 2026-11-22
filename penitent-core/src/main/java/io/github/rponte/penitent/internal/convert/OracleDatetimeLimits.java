package io.github.rponte.penitent.internal.convert;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The range of Oracle's dates, the same for {@code DATE}, {@code TIMESTAMP} and the time zone types: from January 1,
 * 4712 BC, Julian day 1, to December 31, 9999. A value beyond it is refused before it goes to the database.
 *
 * <p><b>The edges, measured on 23ai and 19c.</b> {@code DATE '-4712-01-01'}, which Oracle numbers without a year 0,
 * is {@code TO_DATE('1', 'J')}, and is {@code java.time}'s {@code -4711-01-01}, since {@code java.time} counts a year
 * 0, which is 1 BC. {@code DATE '-4713-01-01'} and {@code DATE '9999-12-31' + 1} fail with {@code ORA-01841}, whose
 * text, "year must be between -4713 and +9999", is one year off at the low end.
 *
 * <p><b>Why the library checks it, and not the driver nor the database.</b> Oracle stores whatever bytes it is sent,
 * and does not check them (#142):
 * <ul>
 * <li>The library builds a {@code DATE}'s and a {@code TIMESTAMP}'s bytes itself, in {@link OracleDatetimeCodec},
 * and so does driver 23's own {@code DATE.of} and {@code TIMESTAMP.of}. The year 10000 was stored as bytes that
 * Oracle itself cannot format ({@code ORA-01801}). The century goes in one byte, so the year 25620 wrapped around
 * into the year 20, a valid and wrong date, that came back to Java as such.</li>
 * <li>Drivers 21 and 23 store an {@code OffsetDateTime} beyond the range as invalid bytes too, through their own
 * conversion.</li>
 * <li>The drivers refuse a {@code java.util.Date} or a {@code java.sql.Timestamp} beyond it, each with a message of
 * its own; checking here gives the same message on every driver.</li>
 * </ul>
 * Driver 21's {@code DATE.of} refused such a year, as did JDBC 11.2.0.1 and later, which fixed the same problem as
 * Oracle's bugs 7271519, 7249052 and 7243157. A value beyond the range has no use: Oracle can neither format nor
 * reliably compare it. So the library refuses it, with no setting to turn the check off.
 *
 * <p>Only the way in is checked: a value already stored is the database's, and is read as it is.
 */
public final class OracleDatetimeLimits {

	/** January 1, 4712 BC, Oracle's first day, at midnight. */
	public static final LocalDateTime MIN = LocalDateTime.of(-4711, 1, 1, 0, 0);

	/** December 31, 9999, Oracle's last day, at its last nanosecond. */
	public static final LocalDateTime MAX = LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_999_999);

	private OracleDatetimeLimits() {
	}

	/**
	 * For a value of any type, as the funnel of every value on its way in, {@code ScalarCodec.encode}, holds it after
	 * the user's converter: a date is checked by its type's {@code requireWithin}; anything else, {@code null}
	 * included, is not a date, and passes. A caller that knows the type calls that {@code requireWithin} directly:
	 * Java picks an overload by the static type, so the funnel's {@code Object} needs this dispatch.
	 *
	 * @throws ScalarCodec.ConversionException as {@link #requireWithin(LocalDateTime)}
	 */
	public static void requireWithinIfDate(Object value) {
		if (value instanceof LocalDate date) {
			requireWithin(date);
		} else if (value instanceof LocalDateTime dateTime) {
			requireWithin(dateTime);
		} else if (value instanceof OffsetDateTime dateTime) {
			requireWithin(dateTime);
		} else if (value instanceof ZonedDateTime dateTime) {
			requireWithin(dateTime);
		} else if (value instanceof java.util.Date date) {
			requireWithin(date);
		}
	}

	/** A {@code LocalDate}, by its own fields. */
	public static void requireWithin(LocalDate value) {
		require(value.atStartOfDay(), value, null);
	}

	/**
	 * A {@code LocalDateTime}, by its own fields.
	 *
	 * @throws ScalarCodec.ConversionException with no context: whoever knows the parameter, the attribute or the
	 *         element adds it
	 */
	public static void requireWithin(LocalDateTime value) {
		require(value, value, null);
	}

	/**
	 * An {@code OffsetDateTime}, by its local fields, which the value shows, and by its instant in UTC, which a
	 * {@code TIMESTAMP WITH TIME ZONE} stores. An {@code Instant} reaches the funnel already as an
	 * {@code OffsetDateTime} in UTC, from {@code InstantConverter}.
	 */
	public static void requireWithin(OffsetDateTime value) {
		requireWithZone(value.toLocalDateTime(), value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime(), value);
	}

	/** A {@code ZonedDateTime}, as an {@code OffsetDateTime}: by its local fields and by its instant in UTC. */
	public static void requireWithin(ZonedDateTime value) {
		requireWithZone(value.toLocalDateTime(), value.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(), value);
	}

	/**
	 * A {@code java.util.Date}, a {@code java.sql.Date} or a {@code java.sql.Timestamp}, by the fields the driver
	 * writes: in the JVM's time zone, and in its calendar, Julian before 1582.
	 */
	public static void requireWithin(java.util.Date value) {
		// a java.sql.Date's toInstant() throws, and a Timestamp's fields are what the driver writes
		Timestamp timestamp = value instanceof Timestamp t ? t : new Timestamp(value.getTime());
		require(timestamp.toLocalDateTime(), value, null);
	}

	private static void requireWithZone(LocalDateTime local, LocalDateTime utc, Object value) {
		require(local, value, null);
		require(utc, value, utc);
	}

	private static void require(LocalDateTime fields, Object value, LocalDateTime utc) {
		if (fields.isBefore(MIN) || fields.isAfter(MAX)) {
			throw new ScalarCodec.ConversionException("the value " + value
					+ (utc == null ? "" : ", which is " + utc + " in UTC,")
					+ " is beyond the range of Oracle's dates, from January 1, 4712 BC (java.time's -4711-01-01) to"
					+ " December 31, 9999: Oracle would store it as an invalid date, or as another date");
		}
	}
}
