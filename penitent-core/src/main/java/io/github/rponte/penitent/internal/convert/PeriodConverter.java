package io.github.rponte.penitent.internal.convert;

import java.time.Period;

import oracle.sql.INTERVALYM;

/** {@code INTERVAL YEAR TO MONTH}. The driver's own type is opened before, in {@link ScalarCodec}. */
final class PeriodConverter implements BuiltInConverter<Period> {

	@Override
	public Object toDatabase(Period value) {
		return INTERVALYM.toIntervalym(value);
	}

	@Override
	public Period fromDatabase(Object raw) {
		if (raw instanceof Period period) {
			return period;
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to java.time.Period");
	}
}
