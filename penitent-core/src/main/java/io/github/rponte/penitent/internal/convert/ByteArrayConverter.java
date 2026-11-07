package io.github.rponte.penitent.internal.convert;

import java.sql.Blob;
import java.sql.SQLException;

/** {@code RAW} arrives as {@code byte[]}, and a {@code BLOB} is read whole. */
final class ByteArrayConverter implements BuiltInConverter<byte[]> {

	@Override
	public byte[] fromDatabase(Object raw) {
		if (raw instanceof byte[] bytes) {
			return bytes;
		}
		if (raw instanceof Blob blob) {
			try {
				return blob.getBytes(1, Math.toIntExact(blob.length()));
			} catch (SQLException e) {
				throw new ScalarCodec.ConversionException("failed to read a BLOB: " + e.getMessage(), e);
			}
		}
		throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to byte[]");
	}
}
