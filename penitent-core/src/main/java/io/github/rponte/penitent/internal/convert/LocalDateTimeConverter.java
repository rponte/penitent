package io.github.rponte.penitent.internal.convert;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * Crosses the driver as {@code java.time}, never as {@link Timestamp}: a {@code Timestamp} is an instant,
 * written and read in the JVM's time zone, so a local time that zone skips, such as 02:30 on the day
 * daylight saving time starts, would move an hour without any error.
 */
final class LocalDateTimeConverter implements BuiltInConverter<LocalDateTime> {

	@Override
	public Object toDatabase(LocalDateTime value) {
		return value;
	}

	@Override
	public LocalDateTime fromDatabase(Object raw) {
		ScalarCodec.requireNoTimeZone(raw, LocalDateTime.class);
		if (raw instanceof LocalDateTime local) {
			return local;
		}
		if (raw instanceof Timestamp t) {
			return t.toLocalDateTime();
		}
		if (raw instanceof java.util.Date d) {
			return new Timestamp(d.getTime()).toLocalDateTime();
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to LocalDateTime");
	}
}
