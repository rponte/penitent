package io.github.rponte.penitent.internal.convert;

// double loses precision coming from NUMBER, but it is what the entity declares; the warning
// belongs in the documentation, not in a rejection: there is legacy code that uses double on purpose
final class DoubleConverter implements BuiltInConverter<Double> {

	@Override
	public Double fromDatabase(Object raw) {
		// straight from the Number: NaN and infinity exist in BINARY_DOUBLE, but not in BigDecimal
		return raw instanceof Number n ? n.doubleValue() : BigDecimalConverter.read(raw).doubleValue();
	}
}
