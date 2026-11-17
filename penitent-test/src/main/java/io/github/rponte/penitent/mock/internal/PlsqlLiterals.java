package io.github.rponte.penitent.mock.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

import io.github.rponte.penitent.internal.convert.OracleIntervalCodec;

import oracle.jdbc.OracleArray;
import oracle.sql.INTERVALDS;
import oracle.sql.INTERVALYM;

/**
 * Java values written as PL/SQL, for the code the mocks generate: numbers, {@code NaN} and the infinities, text, a
 * {@code CLOB} above one literal in pieces, booleans, dates, timestamps, time zones, intervals and bytes up to
 * {@value #MAX_BYTES}; and, through {@link #constructor}, object types, collections, RECORDs, {@code %ROWTYPE} records
 * and {@code INDEX BY} collections, as the driver has them. Each form was measured on Oracle 19c, 21c and 23ai.
 * Any other value is refused with a message that says so, rather than written in a way that might compare wrongly.
 */
public final class PlsqlLiterals {

	private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS",
			Locale.ROOT);

	private PlsqlLiterals() {
	}

	/** {@code NaN} or an infinity, as the constant of {@code BINARY_DOUBLE} or {@code BINARY_FLOAT}. */
	private static String notFinite(double number, String type) {
		return Double.isNaN(number) ? "BINARY_" + type + "_NAN"
				: (number < 0 ? "-" : "") + "BINARY_" + type + "_INFINITY";
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
		if (value instanceof Double number) {
			return Double.isFinite(number) ? new BigDecimal(value.toString()).toPlainString() : notFinite(number, "DOUBLE");
		}
		if (value instanceof Float number) {
			return Float.isFinite(number) ? new BigDecimal(value.toString()).toPlainString()
					: notFinite(number.doubleValue(), "FLOAT");
		}
		if (value instanceof CharSequence || value instanceof Character) {
			return text(value.toString());
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
		if (value instanceof OffsetDateTime dateTime) {
			return "TIMESTAMP '" + TIMESTAMP.format(dateTime) + " " + offset(dateTime.getOffset()) + "'";
		}
		if (value instanceof ZonedDateTime dateTime) {
			// a region keeps the rules of daylight saving time, which an offset alone would lose
			return "TIMESTAMP '" + TIMESTAMP.format(dateTime) + " "
					+ (dateTime.getZone() instanceof ZoneOffset zoneOffset ? offset(zoneOffset) : dateTime.getZone().getId())
					+ "'";
		}
		if (value instanceof Instant instant) {
			return of(instant.atOffset(ZoneOffset.UTC));
		}
		if (value instanceof INTERVALDS interval) {
			return of(OracleIntervalCodec.duration(interval.toBytes()));
		}
		if (value instanceof INTERVALYM interval) {
			return of(OracleIntervalCodec.period(interval.toBytes()));
		}
		if (value instanceof Duration duration) {
			Duration magnitude = duration.abs();
			return String.format(Locale.ROOT, "TO_DSINTERVAL('%s%d %02d:%02d:%02d.%09d')", duration.isNegative() ? "-" : "",
					magnitude.toDays(), magnitude.toHoursPart(), magnitude.toMinutesPart(), magnitude.toSecondsPart(),
					magnitude.toNanosPart());
		}
		if (value instanceof Period period) {
			long months = period.toTotalMonths();
			return String.format(Locale.ROOT, "TO_YMINTERVAL('%s%d-%d')", months < 0 ? "-" : "", Math.abs(months) / 12,
					Math.abs(months) % 12);
		}
		if (value instanceof byte[] bytes) {
			return bytes(bytes);
		}
		if (value instanceof Clob clob) {
			try {
				return text(clob.getSubString(1, Math.toIntExact(clob.length())));
			} catch (SQLException e) {
				throw new IllegalStateException("failed to read a Clob to write it as PL/SQL: " + e.getMessage(), e);
			}
		}
		if (value instanceof Blob blob) {
			try {
				return bytes(blob.getBytes(1, Math.toIntExact(blob.length())));
			} catch (SQLException e) {
				throw new IllegalStateException("failed to read a Blob to write it as PL/SQL: " + e.getMessage(), e);
			}
		}
		throw new IllegalStateException("a " + value.getClass().getName() + " cannot be written as PL/SQL by the mocks");
	}

	/** The most characters a piece of text holds, so that it fits a PL/SQL literal of 32,767 bytes in any charset. */
	private static final int TEXT_PIECE = 8_000;

	/** The most bytes {@code HEXTORAW} takes from one literal, written as two hex digits each. */
	static final int MAX_BYTES = 16_383;

	/**
	 * Text as a PL/SQL literal; above what one literal holds, as {@code TO_CLOB} pieces joined with {@code ||}, which
	 * only a {@code CLOB} takes, and which Oracle 19c, 21c and 23ai compile.
	 */
	private static String text(String text) {
		if (text.length() <= TEXT_PIECE) {
			return "'" + text.replace("'", "''") + "'";
		}
		StringJoiner pieces = new StringJoiner(" || ");
		int start = 0;
		while (start < text.length()) {
			int end = Math.min(start + TEXT_PIECE, text.length());
			// a surrogate pair stays in one piece
			if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) {
				end--;
			}
			pieces.add("TO_CLOB('" + text.substring(start, end).replace("'", "''") + "')");
			start = end;
		}
		return pieces.toString();
	}

	/** Bytes as {@code HEXTORAW}, which a {@code RAW} and a {@code BLOB} take; above one literal, refused. */
	private static String bytes(byte[] bytes) {
		if (bytes.length > MAX_BYTES) {
			throw new IllegalStateException("a value of " + bytes.length + " bytes cannot be written in a stub yet: a"
					+ " PL/SQL literal holds " + MAX_BYTES + " bytes as hex digits, and a BLOB cannot be joined in an"
					+ " expression; see https://github.com/rponte/penitent/issues/93");
		}
		return "HEXTORAW('" + java.util.HexFormat.of().withUpperCase().formatHex(bytes) + "')";
	}

	/** {@code +hh:mm}, as Oracle reads an offset, also for UTC, which Java writes as {@code Z}. */
	private static String offset(ZoneOffset offset) {
		return offset.equals(ZoneOffset.UTC) ? "+00:00" : offset.getId();
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
