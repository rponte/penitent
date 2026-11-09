package io.github.rponte.penitent.internal.convert;

import java.time.Period;

/**
 * {@code INTERVAL YEAR TO MONTH}. The driver's own type is built by {@link OracleIntervalCodec}, which works on
 * every driver version, and opened before, in {@link ScalarCodec}.
 */
final class PeriodConverter implements BuiltInConverter<Period> {

	@Override
	public Object toDatabase(Period value) {
		return OracleIntervalCodec.encode(value);
	}

	@Override
	public Period fromDatabase(Object raw) {
		if (raw instanceof Period period) {
			return period;
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to java.time.Period");
	}
}
