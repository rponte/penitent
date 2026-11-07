package io.github.rponte.penitent.internal.convert;

import java.time.Instant;
import java.time.ZoneOffset;

final class InstantConverter implements BuiltInConverter<Instant> {

	// the driver does not accept Instant, and without an offset there is nothing to store: it goes as UTC
	@Override
	public Object toDatabase(Instant value) {
		return value.atOffset(ZoneOffset.UTC);
	}

	@Override
	public Instant fromDatabase(Object raw) {
		return ZonedDateTimeConverter.read(raw, Instant.class).toInstant();
	}
}
