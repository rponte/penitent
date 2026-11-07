package io.github.rponte.penitent.internal.convert;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Crosses the driver as {@code java.time}, never as {@link Date}, for the reason in
 * {@link LocalDateTimeConverter}: on the day daylight saving time starts at midnight, as it used to in
 * São Paulo, a {@code java.sql.Date} has no midnight to hold, and the {@code DATE} would get 01:00.
 */
final class LocalDateConverter implements BuiltInConverter<LocalDate> {

	@Override
	public Object toDatabase(LocalDate value) {
		return value;
	}

	@Override
	public LocalDate fromDatabase(Object raw) {
		ScalarCodec.requireNoTimeZone(raw, LocalDate.class);
		if (raw instanceof LocalDate date) {
			return date;
		}
		if (raw instanceof LocalDateTime local) {
			return local.toLocalDate();
		}
		if (raw instanceof Date d) {
			return d.toLocalDate();
		}
		if (raw instanceof Timestamp t) {
			return t.toLocalDateTime().toLocalDate();
		}
		if (raw instanceof java.util.Date d) {
			return new Date(d.getTime()).toLocalDate();
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to LocalDate");
	}
}
