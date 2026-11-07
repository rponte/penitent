package io.github.rponte.penitent.internal.convert;

import java.sql.Blob;
import java.sql.SQLException;

import javax.sql.rowset.serial.SerialBlob;

/**
 * A copy in memory: the driver's {@code Blob} depends on the connection, which goes back to the pool
 * when the call ends.
 */
final class BlobConverter implements BuiltInConverter<Blob> {

	@Override
	public Blob fromDatabase(Object raw) {
		try {
			return new SerialBlob(new ByteArrayConverter().fromDatabase(raw));
		} catch (SQLException e) {
			throw new ScalarCodec.ConversionException("failed to read a BLOB: " + e.getMessage(), e);
		}
	}
}
