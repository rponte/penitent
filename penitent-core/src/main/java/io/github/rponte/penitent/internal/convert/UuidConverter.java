package io.github.rponte.penitent.internal.convert;

import java.util.UUID;

/** As text, in the 36-character form of {@link UUID#toString()}. */
final class UuidConverter implements BuiltInConverter<UUID> {

	@Override
	public Object toDatabase(UUID value) {
		return value.toString();
	}

	@Override
	public UUID fromDatabase(Object raw) {
		return UUID.fromString(new StringConverter().fromDatabase(raw).strip());
	}
}
