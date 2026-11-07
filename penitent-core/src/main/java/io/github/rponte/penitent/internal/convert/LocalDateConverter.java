package io.github.rponte.penitent.internal.convert;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;

final class LocalDateConverter implements BuiltInConverter<LocalDate> {

	@Override
	public Object toDatabase(LocalDate value) {
		return Date.valueOf(value);
	}

	@Override
	public LocalDate fromDatabase(Object raw) {
		ScalarCodec.requireNoTimeZone(raw, LocalDate.class);
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
