package io.github.rponte.penitent.internal.convert;

import java.math.BigDecimal;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

/** Boolean through the literals already decided, by {@link PlsqlBoolean} or by the dictionary. */
record BooleanConverter(ValueConversion conversion) implements BuiltInConverter<Boolean> {

	@Override
	public Object toDatabase(Boolean value) {
		return value ? conversion.trueValue() : conversion.falseValue();
	}

	/** A text that is neither of the two literals is an error: turning it into false would hide invalid data. */
	@Override
	public Boolean fromDatabase(Object raw) {
		// A real BOOLEAN is what comes back from an anonymous block on 23ai. Comparing it with
		// 'Y' in that case would silently yield false, because "true".equalsIgnoreCase("Y") is false,
		// and a silent false is worse than an error.
		if (raw instanceof Boolean b) {
			return b;
		}
		// with no declared literals, a number can only be 1 or 0: it will never be a Y
		ValueConversion literals = raw instanceof Number && conversion == ValueConversion.DEFAULT
				? ValueConversion.NUMERIC_BOOLEAN
				: conversion;
		String text = raw instanceof Number
				? new BigDecimal(raw.toString()).stripTrailingZeros().toPlainString()
				: raw.toString().strip();
		if (literals.trueValue().equalsIgnoreCase(text)) {
			return true;
		}
		if (literals.falseValue().equalsIgnoreCase(text)) {
			return false;
		}
		throw new ScalarCodec.ConversionException("cannot convert '" + text + "' to boolean: expected '"
				+ literals.trueValue() + "' or '" + literals.falseValue()
				+ "'. Use @PlsqlBoolean to declare other literals.");
	}
}
