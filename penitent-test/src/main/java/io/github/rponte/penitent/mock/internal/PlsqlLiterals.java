package io.github.rponte.penitent.mock.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Java values written as PL/SQL literals, for the code the mocks generate. The first version knows the scalars:
 * numbers, text, booleans, {@code LocalDate} and {@code LocalDateTime}; any other value is refused with a message
 * that says so, rather than written in a way that might compare wrongly.
 */
public final class PlsqlLiterals {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS",
			Locale.ROOT);

	private PlsqlLiterals() {
	}

	public static String of(Object value) {
		if (value == null) {
			return "NULL";
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
}
