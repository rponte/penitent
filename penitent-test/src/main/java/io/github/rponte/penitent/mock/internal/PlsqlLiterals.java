package io.github.rponte.penitent.mock.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import oracle.jdbc.OracleArray;

/**
 * Java values written as PL/SQL literals, for the code the mocks generate: numbers, text, booleans, {@code LocalDate}
 * and {@code LocalDateTime}; and, through {@link #constructor}, object types, collections, RECORDs, {@code %ROWTYPE}
 * records and {@code INDEX BY} collections, as the driver has them.
 * Any other value is refused with a message that says so, rather than written in a way that might compare wrongly.
 */
public final class PlsqlLiterals {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS",
			Locale.ROOT);

	private PlsqlLiterals() {
	}

	/**
	 * How PL/SQL writes a value of a type the driver names: the type mark of its constructor or qualified expression,
	 * and whether its elements go with named indexes, as those of an {@code INDEX BY} do.
	 */
	public record Mark(String name, boolean indexed) {
	}

	/** The {@link Mark} of each type the driver names, which the dictionary tells. */
	@FunctionalInterface
	public interface TypeMarks {
		Mark of(String driverName) throws SQLException;
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
	 * type, or the qualified expression of its RECORD, with the attributes in the type's order, which the core follows
	 * whatever the order of the Java fields; an {@code Array} as the constructor of its collection, or, for an
	 * {@code INDEX BY}, a qualified expression with named indexes from 1, which Oracle 19c requires; and the rest as
	 * literals. {@code marks} names each type as PL/SQL writes it, owner included, so it resolves in any schema.
	 */
	public static Written constructor(Object encoded, TypeMarks marks) throws SQLException {
		return new Written(write(encoded, marks));
	}

	private static String write(Object value, TypeMarks marks) throws SQLException {
		if (value == null) {
			return "NULL";
		}
		if (value instanceof Struct struct) {
			return arguments(marks.of(struct.getSQLTypeName()), struct.getAttributes(), marks);
		}
		if (value instanceof OracleArray array) {
			// java.sql.Array has the element's type, not its own: the driver's does
			return arguments(marks.of(array.getSQLTypeName()), (Object[]) array.getArray(), marks);
		}
		if (value instanceof Timestamp timestamp) {
			// how the driver hands back a DATE or a TIMESTAMP: as a TIMESTAMP literal, it converts to either one
			return of(timestamp.toLocalDateTime());
		}
		return of(value);
	}

	private static String arguments(Mark type, Object[] values, TypeMarks marks) throws SQLException {
		StringJoiner joined = new StringJoiner(", ", type.name() + "(", ")");
		for (int i = 0; i < values.length; i++) {
			joined.add((type.indexed() ? (i + 1) + " => " : "") + write(values[i], marks));
		}
		return joined.toString();
	}

	/**
	 * The parts of a name the driver gives, each with its exact case and without quotes: {@code SCHEMA.TYPE},
	 * {@code SCHEMA.PACKAGE.TYPE}, or, from driver 23, {@code "SCHEMA"."PACKAGE.TYPE"}, whose second part holds two.
	 * The {@code %ROWTYPE} the driver may keep at the end of a table's name is dropped.
	 */
	public static List<String> parts(String driverName) {
		List<String> parts = new ArrayList<>();
		StringBuilder part = new StringBuilder();
		boolean quoted = false;
		for (char c : driverName.toCharArray()) {
			if (c == '"') {
				quoted = !quoted;
			} else if (c == '.' && !quoted) {
				parts.add(part.toString());
				part.setLength(0);
			} else {
				part.append(c);
			}
		}
		parts.add(part.toString());
		List<String> split = new ArrayList<>();
		for (String each : parts) {
			split.addAll(List.of(each.split("\\.")));
		}
		String last = split.get(split.size() - 1);
		if (last.toUpperCase(Locale.ROOT).endsWith("%ROWTYPE")) {
			split.set(split.size() - 1, last.substring(0, last.length() - "%ROWTYPE".length()));
		}
		return split;
	}

	/** {@code parts} joined by dots, each in quotes, keeping its case. */
	public static String quoted(List<String> parts) {
		StringJoiner quoted = new StringJoiner(".");
		parts.forEach(part -> quoted.add("\"" + part + "\""));
		return quoted.toString();
	}
}
