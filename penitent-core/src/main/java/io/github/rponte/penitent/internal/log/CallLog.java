package io.github.rponte.penitent.internal.log;

import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.internal.metadata.Dictionary;

/**
 * The log of a call. There are two loggers, so that turning on the SQL does not expose the data:
 *
 * <ul>
 * <li>{@value #CALL}, at debug: the routine or block, the SQL as sent to the driver, the elapsed
 * time, the failure and the dictionary lookup;
 * <li>{@value #BIND}, at trace: the values sent, the return value and the {@code OUT} values.
 * </ul>
 *
 * <p>Values are shown as they were sent to the driver, not through Java's {@code toString}: an
 * OBJECT shows up as {@code ADDRESS_T('Street A', 1)}. Long text and large collections are truncated.
 *
 * <p>From {@value #ONE_PER_LINE} parameters on, the values are shown one per line, with the names
 * aligned, and so is the SQL of a call by name. By position, the SQL stays on one line: one
 * {@code ?} per line would show nothing.
 *
 * <p>A collection with more than one element is shown one element per line, and each line is a
 * separate log record, with the logger's prefix: {@code grep} still finds the logger and the level.
 * A line break inside a value is shown as {@code \n}, so that no value can create a line that looks
 * like a record from the library.
 */
public final class CallLog {

	static final String CALL = "io.github.rponte.penitent.call";
	static final String BIND = "io.github.rponte.penitent.bind";

	private static final Logger CALLS = LoggerFactory.getLogger(CALL);
	private static final Logger BINDS = LoggerFactory.getLogger(BIND);

	/** As in jOOQ: enough to recognize the value, without flooding the log. */
	static final int MAX_TEXT = 2000;
	static final int MAX_ELEMENTS = 5;

	/** The width of the labels, such as {@code "-> with bind values"}; a continuation line aligns with the value. */
	private static final int LABEL = 19;
	private static final String CONTINUATION = " ".repeat(LABEL + 3);

	/** From how many parameters on each one gets its own line. */
	static final int ONE_PER_LINE = 3;

	private final long start = System.nanoTime();
	private final List<String[]> sent;
	private final List<String[]> outs;

	private CallLog() {
		boolean trace = BINDS.isTraceEnabled();
		this.sent = trace ? new ArrayList<>() : null;
		this.outs = trace ? new ArrayList<>() : null;
	}

	/**
	 * @param names the names of a call by name, in SQL order, or {@code null} for a call by
	 *        position
	 */
	public static CallLog routine(String qualifiedName, Method method, String sql, List<String> names) {
		if (CALLS.isDebugEnabled()) {
			CALLS.debug("Calling routine     : {} <- {}", qualifiedName, javaName(method));
			lines(CALLS, false, "-> SQL", names == null || names.size() < ONE_PER_LINE ? sql : byName(sql, names));
		}
		return new CallLog();
	}

	/** The SQL as sent to the driver, on one line, here with one parameter per line. */
	private static String byName(String sql, List<String> names) {
		int width = names.stream().mapToInt(String::length).max().orElse(0);
		List<String> parameters = new ArrayList<>();
		for (String name : names) {
			parameters.add("  " + pad(name, width) + " => ?");
		}
		return sql.substring(0, sql.indexOf('(') + 1) + "\n" + String.join(",\n", parameters) + "\n)}";
	}

	private static String pad(String name, int width) {
		return name + " ".repeat(width - name.length());
	}

	public static CallLog block(Method method, String sql) {
		if (CALLS.isDebugEnabled()) {
			CALLS.debug("Calling block       : {}", javaName(method));
			lines(CALLS, false, "-> SQL", sql.strip());
		}
		return new CallLog();
	}

	public static void described(String qualifiedName, long startNanos) {
		if (CALLS.isDebugEnabled()) {
			CALLS.debug("Read dictionary     : {} in {} ms", qualifiedName, elapsedMillis(startNanos));
		}
	}

	/**
	 * At warn, unlike the rest of this log: a value the driver will read wrong, in silence. The call
	 * itself goes on, since true and false still read right. See {@code NullBooleanOutputs}.
	 *
	 * @param what the routine or block and how the BOOLEAN comes back, as in
	 *        {@code "PKG.F returns a BOOLEAN"}
	 */
	public static void nullBooleanOutputMisread(String what, String driverVersion, int databaseVersion) {
		CALLS.warn("{}, and with Oracle driver {} against Oracle Database {}, a null BOOLEAN that comes back reads as"
				+ " true or false: driver 23 misreads it from a database before 23ai. Use the driver of the database's"
				+ " version, such as 21 or 19, or have the PL/SQL hand the value back as VARCHAR2 or NUMBER, as in"
				+ " CASE WHEN flag THEN 'Y' WHEN NOT flag THEN 'N' END, and declare Boolean. See the compatibility page"
				+ " of the documentation", what, driverVersion, databaseVersion);
	}

	public void sent(String name, Object value) {
		if (sent != null) {
			sent.add(new String[] { name, format(value) });
		}
	}

	public void result(Object value) {
		if (BINDS.isTraceEnabled()) {
			lines(BINDS, true, "<- result", format(value));
		}
	}

	public void out(String name, Object value) {
		if (outs != null) {
			outs.add(new String[] { name, format(value) });
		}
	}

	/** All the {@code OUT} values in a single entry, like the values sent. */
	public void outsRead() {
		if (outs != null && !outs.isEmpty()) {
			lines(BINDS, true, "<- OUT values", values(outs));
		}
	}

	/** Before executing, because that is when everything that was sent is known. */
	public void executing() {
		if (sent != null) {
			lines(BINDS, true, "-> with bind values", sent.isEmpty() ? "(none)" : values(sent));
		}
	}

	/** {@code A=1, B=2} on one line or, from {@value #ONE_PER_LINE} on, one per line and aligned. */
	private static String values(List<String[]> values) {
		if (values.size() < ONE_PER_LINE) {
			List<String> inline = new ArrayList<>();
			for (String[] value : values) {
				inline.add(value[0] + "=" + value[1]);
			}
			return String.join(", ", inline);
		}
		int width = values.stream().mapToInt(value -> value[0].length()).max().orElse(0);
		List<String> lines = new ArrayList<>();
		for (String[] value : values) {
			// the lines inside a value, such as the elements of a collection, get the same indentation
			lines.add("  " + pad(value[0], width) + " = " + value[1].replace("\n", "\n  "));
		}
		return values.size() + " parameters\n" + String.join(",\n", lines);
	}

	/** One log record per line, and only the first one with the label. */
	private static void lines(Logger logger, boolean trace, String label, String text) {
		String[] lines = text.split("\n", -1);
		String first = String.format(Locale.ROOT, "%-" + LABEL + "s : %s", label, lines[0]);
		log(logger, trace, first);
		for (int i = 1; i < lines.length; i++) {
			log(logger, trace, CONTINUATION + lines[i]);
		}
	}

	private static void log(Logger logger, boolean trace, String line) {
		if (trace) {
			logger.trace(line);
		} else {
			logger.debug(line);
		}
	}

	public void completed() {
		if (CALLS.isDebugEnabled()) {
			CALLS.debug("<- completed        : {} ms", elapsedMillis(start));
		}
	}

	/** One line, without a stack trace: the exception already reaches the caller. */
	public void failed(RuntimeException e) {
		if (CALLS.isDebugEnabled()) {
			Throwable cause = e instanceof Dictionary.SqlFailure && e.getCause() != null ? e.getCause() : e;
			CALLS.debug("<- failed           : {} ms: {}: {}", elapsedMillis(start), cause.getClass().getSimpleName(),
					oneLine(cause.getMessage()));
		}
	}

	private static long elapsedMillis(long startNanos) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
	}

	/** The interface and the method, as in {@code Orders.place}. */
	public static String javaName(Method method) {
		return method.getDeclaringClass().getSimpleName() + "." + method.getName();
	}

	private static String oneLine(String message) {
		return message == null ? "" : message.strip().replaceAll("\\s*\\R\\s*", " | ");
	}

	static String format(Object value) {
		try {
			return formatValue(value);
		} catch (SQLException | RuntimeException e) {
			// logging never brings the call down
			return "(could not format " + value.getClass().getSimpleName() + ")";
		}
	}

	private static String formatValue(Object value) throws SQLException {
		if (value == null) {
			return "NULL";
		}
		if (value instanceof PlsqlOut<?> out) {
			return format(out.get());
		}
		if (value instanceof CharSequence text) {
			return "'" + escape(abbreviate(text.toString())).replace("'", "''") + "'";
		}
		if (value instanceof Struct struct) {
			List<String> attributes = new ArrayList<>();
			for (Object attribute : struct.getAttributes()) {
				attributes.add(format(attribute));
			}
			return struct.getSQLTypeName() + "(" + String.join(", ", attributes) + ")";
		}
		if (value instanceof Array array) {
			return elements(java.util.Arrays.asList((Object[]) array.getArray()));
		}
		if (value instanceof Clob clob) {
			return "CLOB(" + clob.length() + " chars)";
		}
		if (value instanceof Blob blob) {
			return "BLOB(" + blob.length() + " bytes)";
		}
		if (value instanceof byte[] bytes) {
			return "byte[" + bytes.length + "]";
		}
		if (value instanceof ResultSet) {
			return "cursor";
		}
		if (value instanceof Collection<?> collection) {
			return elements(collection);
		}
		if (value instanceof Object[] elements) {
			return elements(java.util.Arrays.asList(elements));
		}
		if (value.getClass().isArray()) {
			List<Object> elements = new ArrayList<>();
			for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
				elements.add(java.lang.reflect.Array.get(value, i));
			}
			return elements(elements);
		}
		return escape(abbreviate(String.valueOf(value)));
	}

	/** With more than one element, one per line; the lines inside an element get one more level of indentation. */
	private static String elements(Collection<?> elements) {
		List<String> shown = new ArrayList<>();
		for (Object element : elements) {
			if (shown.size() == MAX_ELEMENTS) {
				break;
			}
			shown.add(format(element));
		}
		if (elements.size() <= 1) {
			return "[" + String.join(", ", shown) + "]";
		}
		if (elements.size() > MAX_ELEMENTS) {
			shown.add("... (" + elements.size() + " elements)");
		}
		return "[\n  " + String.join(",\n", shown).replace("\n", "\n  ") + "\n]";
	}

	/** Structural line breaks are only the library's own: a line break in a value becomes text. */
	private static String escape(String text) {
		return text.replace("\r", "\\r").replace("\n", "\\n");
	}

	private static String abbreviate(String text) {
		return text.length() <= MAX_TEXT
				? text
				: text.substring(0, MAX_TEXT) + "... (" + text.length() + " chars)";
	}
}
