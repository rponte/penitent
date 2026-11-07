package io.github.rponte.penitent.internal.convert;

final class IntegerConverter implements BuiltInConverter<Integer> {

	@Override
	public Integer fromDatabase(Object raw) {
		return BigDecimalConverter.read(raw).intValueExact();
	}
}
