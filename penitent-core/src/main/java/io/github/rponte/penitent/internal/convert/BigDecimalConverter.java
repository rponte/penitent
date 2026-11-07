package io.github.rponte.penitent.internal.convert;

import java.math.BigDecimal;

final class BigDecimalConverter implements BuiltInConverter<BigDecimal> {

	@Override
	public BigDecimal fromDatabase(Object raw) {
		return read(raw);
	}

	/** Also the read path for the other numeric types, which start from here. */
	static BigDecimal read(Object raw) {
		if (raw instanceof BigDecimal b) {
			return b;
		}
		if (raw instanceof Number n) {
			return new BigDecimal(n.toString());
		}
		if (raw instanceof String s) {
			return new BigDecimal(s.strip());
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to BigDecimal");
	}
}
