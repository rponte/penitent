package io.github.rponte.penitent.mock.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.StringJoiner;

import oracle.jdbc.OracleArray;

/**
 * Java values written as PL/SQL literals, for the code the mocks generate: numbers, text, booleans, {@code LocalDate}
 * and {@code LocalDateTime}; and, through {@link #constructor}, object types and collections, as the driver has them.
 * Any other value is refused with a message that says so, rather than written in a way that might compare wrongly.
 */
public final class PlsqlLiterals {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS",
			Locale.ROOT);

	private PlsqlLiterals() {
	}

	/** A value already written as PL/SQL, such as the constructor of an object type. */
	public record Written(String sql) {
		@Override
		public String toString() {
			return sql;
		}
	}

	public static String of(Object value) {
		if (value == null) {
			return "NULL";
		}
		if (value instanceof Written written) {
			return written.sql();
		}
		if (value instanceof BigDecimal decimal) {
			return decimal.toPlainString();
		}
		if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
				|| value instanceof BigInteger) {
			return value.toString();
		}
		if (value instanceof Double || value instanceof Float) {
			return new BigDecimal(value.toString()).toPlainString();
		}
		if (value instanceof CharSequence || value instanceof Character) {
			return "'" + value.toString().replace("'", "''") + "'";
		}
		if (value instanceof Boolean bool) {
			return bool ? "TRUE" : "FALSE";
		}
		if (value instanceof LocalDate date) {
			return "DATE '" + date + "'";
		}
		if (value instanceof LocalDateTime dateTime) {
			return "TIMESTAMP '" + TIMESTAMP.format(dateTime) + "'";
		}
		throw new IllegalStateException("a " + value.getClass().getName() + " cannot be written as PL/SQL by the mocks"
				+ " yet: they know numbers, text, booleans, LocalDate and LocalDateTime");
	}

	/**
	 * A value the core encoded for the driver, written as PL/SQL: a {@code Struct} as the constructor of its object
	 * type, with the attributes in the type's order, an {@code Array} as the constructor of its collection, and the
	 * rest as literals. The names come from the driver, owner included, so the constructor resolves in any schema.
	 */
	public static Written constructor(Object encoded) throws SQLException {
		return new Written(write(encoded));
	}

	private static String write(Object value) throws SQLException {
		if (value == null) {
			return "NULL";
		}
		if (value instanceof Struct struct) {
			String type = struct.getSQLTypeName();
			if (type.split("\\.").length > 2) {
				throw new IllegalStateException("an element of " + type + ", a RECORD of a package, cannot be written as"
						+ " PL/SQL by the mocks yet: they write object types and collections");
			}
			return arguments(name(type), struct.getAttributes());
		}
		if (value instanceof OracleArray array) {
			// java.sql.Array has the element's type, not its own: the driver's does
			return arguments(name(array.getSQLTypeName()), (Object[]) array.getArray());
		}
		if (value instanceof Timestamp timestamp) {
			// how the driver hands back a DATE or a TIMESTAMP: as a TIMESTAMP literal, it converts to either one
			return of(timestamp.toLocalDateTime());
		}
		return of(value);
	}

	private static String arguments(String type, Object[] values) throws SQLException {
		StringJoiner joined = new StringJoiner(", ", type + "(", ")");
		for (Object element : values) {
			joined.add(write(element));
		}
		return joined.toString();
	}

	/** {@code SCHEMA.TYPE}, or {@code SCHEMA.PACKAGE.TYPE}, with each part in quotes, keeping its case. */
	private static String name(String driverName) {
		StringJoiner quoted = new StringJoiner(".");
		for (String part : driverName.split("\\.")) {
			quoted.add(part.startsWith("\"") ? part : "\"" + part + "\"");
		}
		return quoted.toString();
	}
}
