package io.github.rponte.penitent.internal.convert;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

final class ZonedDateTimeConverter implements BuiltInConverter<ZonedDateTime> {

	@Override
	public ZonedDateTime fromDatabase(Object raw) {
		return read(raw, ZonedDateTime.class);
	}

	/** Also the read path for types that hold an instant: {@code targetType} is what shows up in the error. */
	static ZonedDateTime read(Object raw, Class<?> targetType) {
		if (raw instanceof ZonedDateTime z) {
			return z;
		}
		if (raw instanceof OffsetDateTime o) {
			return o.toZonedDateTime();
		}
		if (raw instanceof java.util.Date) {
			throw new ScalarCodec.ConversionException(targetType.getSimpleName()
					+ " would need a time zone, and the value has none; declare LocalDateTime");
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to "
				+ targetType.getName());
	}
}
