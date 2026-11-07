package io.github.rponte.penitent.internal.convert;

import java.sql.Clob;
import java.sql.SQLException;

import javax.sql.rowset.serial.SerialClob;

/**
 * A copy in memory: the driver's {@code Clob} depends on the connection, which goes back to the pool
 * when the call ends.
 */
final class ClobConverter implements BuiltInConverter<Clob> {

	@Override
	public Clob fromDatabase(Object raw) {
		try {
			if (raw instanceof Clob clob) {
				return new SerialClob(clob.getSubString(1, Math.toIntExact(clob.length())).toCharArray());
			}
			if (raw instanceof String text) {
				return new SerialClob(text.toCharArray());
			}
		} catch (SQLException e) {
			throw new ScalarCodec.ConversionException("failed to read a CLOB: " + e.getMessage(), e);
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to java.sql.Clob");
	}
}
