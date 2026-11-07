package io.github.rponte.penitent.internal.convert;

final class ShortConverter implements BuiltInConverter<Short> {

	@Override
	public Short fromDatabase(Object raw) {
		return BigDecimalConverter.read(raw).shortValueExact();
	}
}
