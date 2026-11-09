package io.github.rponte.penitent.internal.convert;

import java.time.Duration;

/**
 * {@code INTERVAL DAY TO SECOND}. The driver's own type is built by {@link OracleIntervalCodec}, which works on
 * every driver version, and opened before, in {@link ScalarCodec}.
 */
final class DurationConverter implements BuiltInConverter<Duration> {

	@Override
	public Object toDatabase(Duration value) {
		return OracleIntervalCodec.encode(value);
	}

	@Override
	public Duration fromDatabase(Object raw) {
		if (raw instanceof Duration duration) {
			return duration;
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to java.time.Duration");
	}
}
