package io.github.rponte.penitent.internal.convert;

import java.sql.Timestamp;
import java.util.Date;

/**
 * As a {@link Timestamp}, so the time of a {@code DATE} is kept. Reading always returns a
 * {@code java.util.Date}, and not a subclass: {@code Timestamp.equals(Date)} is false.
 */
final class UtilDateConverter implements BuiltInConverter<Date> {

	@Override
	public Object toDatabase(Date value) {
		return new Timestamp(value.getTime());
	}

	@Override
	public Date fromDatabase(Object raw) {
		ScalarCodec.requireNoTimeZone(raw, Date.class);
		if (raw instanceof Date date) {
			return new Date(date.getTime());
		}
		if (raw instanceof java.time.LocalDateTime local) {
			// a java.util.Date lives in the JVM's time zone by definition: this is the one place it applies
			return new Date(Timestamp.valueOf(local).getTime());
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to java.util.Date");
	}
}
