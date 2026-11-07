package io.github.rponte.penitent.internal.convert;

import java.time.OffsetDateTime;

final class OffsetDateTimeConverter implements BuiltInConverter<OffsetDateTime> {

	@Override
	public OffsetDateTime fromDatabase(Object raw) {
		return ZonedDateTimeConverter.read(raw, OffsetDateTime.class).toOffsetDateTime();
	}
}
