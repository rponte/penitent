package io.github.rponte.penitent.internal.convert;

final class FloatConverter implements BuiltInConverter<Float> {

	@Override
	public Float fromDatabase(Object raw) {
		return raw instanceof Number n ? n.floatValue() : BigDecimalConverter.read(raw).floatValue();
	}
}
