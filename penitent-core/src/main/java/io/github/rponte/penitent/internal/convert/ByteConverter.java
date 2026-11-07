package io.github.rponte.penitent.internal.convert;

final class ByteConverter implements BuiltInConverter<Byte> {

	@Override
	public Byte fromDatabase(Object raw) {
		return BigDecimalConverter.read(raw).byteValueExact();
	}
}
