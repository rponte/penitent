package io.github.rponte.penitent.internal.convert;

import java.sql.Timestamp;
import java.time.LocalDateTime;

final class LocalDateTimeConverter implements BuiltInConverter<LocalDateTime> {

	@Override
	public Object toDatabase(LocalDateTime value) {
		return Timestamp.valueOf(value);
	}

	@Override
	public LocalDateTime fromDatabase(Object raw) {
		ScalarCodec.requireNoTimeZone(raw, LocalDateTime.class);
		if (raw instanceof Timestamp t) {
			return t.toLocalDateTime();
		}
		if (raw instanceof java.util.Date d) {
			return new Timestamp(d.getTime()).toLocalDateTime();
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to LocalDateTime");
	}
}
