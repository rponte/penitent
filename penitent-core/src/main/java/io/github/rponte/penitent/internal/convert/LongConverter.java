package io.github.rponte.penitent.internal.convert;

final class LongConverter implements BuiltInConverter<Long> {

	@Override
	public Long fromDatabase(Object raw) {
		return BigDecimalConverter.read(raw).longValueExact();
	}
}
