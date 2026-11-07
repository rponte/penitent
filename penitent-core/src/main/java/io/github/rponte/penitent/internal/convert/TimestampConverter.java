package io.github.rponte.penitent.internal.convert;

import java.sql.Timestamp;
import java.util.Date;

final class TimestampConverter implements BuiltInConverter<Timestamp> {

	@Override
	public Timestamp fromDatabase(Object raw) {
		ScalarCodec.requireNoTimeZone(raw, Timestamp.class);
		if (raw instanceof Timestamp timestamp) {
			return timestamp;
		}
		if (raw instanceof java.time.LocalDateTime local) {
			// a Timestamp lives in the JVM's time zone by definition: this is the one place it applies
			return Timestamp.valueOf(local);
		}
		if (raw instanceof Date date) {
			return new Timestamp(date.getTime());
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName()
				+ " to java.sql.Timestamp");
	}
}
