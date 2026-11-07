package io.github.rponte.penitent.internal.convert;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Struct;

final class StringConverter implements BuiltInConverter<String> {

	/**
	 * The {@code toString} of these types does not carry the content, but something like
	 * {@code oracle.sql.BLOB@1d585fb}: returning that would be a wrong value without any error.
	 */
	@Override
	public String fromDatabase(Object raw) {
		if (raw instanceof Clob clob) {
			try {
				return clob.getSubString(1, Math.toIntExact(clob.length()));
			} catch (SQLException e) {
				throw new ScalarCodec.ConversionException("failed to read a CLOB: " + e.getMessage());
			}
		}
		if (raw instanceof Blob || raw instanceof Struct || raw instanceof java.sql.Array
				|| raw instanceof ResultSet || raw.getClass().isArray()) {
			throw new ScalarCodec.ConversionException("cannot convert " + raw.getClass().getName() + " to String");
		}
		return raw.toString();
	}
}
