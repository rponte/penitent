package io.github.rponte.penitent.internal.convert;

/** Enum by its {@code name()}. */
record EnumConverter<E extends Enum<E>>(Class<E> type) implements BuiltInConverter<E> {

	@Override
	public Object toDatabase(E value) {
		return value.name();
	}

	@Override
	public E fromDatabase(Object raw) {
		return Enum.valueOf(type, raw.toString().strip());
	}
}
