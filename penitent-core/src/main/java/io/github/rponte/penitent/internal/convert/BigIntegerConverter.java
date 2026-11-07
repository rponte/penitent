package io.github.rponte.penitent.internal.convert;

import java.math.BigDecimal;
import java.math.BigInteger;

final class BigIntegerConverter implements BuiltInConverter<BigInteger> {

	// the driver does not accept BigInteger
	@Override
	public Object toDatabase(BigInteger value) {
		return new BigDecimal(value);
	}

	/** A fraction is an error, instead of being truncated. */
	@Override
	public BigInteger fromDatabase(Object raw) {
		return BigDecimalConverter.read(raw).toBigIntegerExact();
	}
}
