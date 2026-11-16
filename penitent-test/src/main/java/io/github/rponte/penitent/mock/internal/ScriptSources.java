package io.github.rponte.penitent.mock.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Where a script comes from, read as UTF-8: the classpath, by default or with {@code classpath:}, or the file system,
 * with {@code file:} or a {@link Path}, or a stream, which is left open. A location is only ever a location: SQL given
 * as one is refused, rather than run because it looked like SQL.
 */
public final class ScriptSources {

	private static final Pattern SQL = Pattern.compile("(?is)\\s*(CREATE|ALTER|DROP|INSERT|UPDATE|DELETE|MERGE|SELECT|WITH"
			+ "|BEGIN|DECLARE|GRANT|REVOKE|TRUNCATE)\\b.*|.*[;\\n].*");

	private ScriptSources() {
	}

	/** The text of the script at {@code location}. */
	public static String read(String location) {
		if (location == null || location.isBlank()) {
			throw new IllegalArgumentException("the location of a script is required");
		}
		if (SQL.matcher(location).matches()) {
			throw new IllegalStateException("applyScript takes the location of a script, such as /mocks/view.sql, and"
					+ " this looks like SQL: give SQL to applyScriptText");
		}
		if (location.toLowerCase(Locale.ROOT).startsWith("file:")) {
			return read(Path.of(location.substring("file:".length())), location);
		}
		String resource = location.toLowerCase(Locale.ROOT).startsWith("classpath:")
				? location.substring("classpath:".length()) : location;
		String name = resource.startsWith("/") ? resource.substring(1) : resource;
		ClassLoader loader = Thread.currentThread().getContextClassLoader();
		try (InputStream in = (loader == null ? ScriptSources.class.getClassLoader() : loader).getResourceAsStream(name)) {
			if (in == null) {
				throw new IllegalStateException(location + " is not on the classpath; for a file, write file:" + resource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("failed to read " + location, e);
		}
	}

	public static String read(Path file) {
		return read(file, file.toString());
	}

	private static String read(Path file, String location) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		} catch (NoSuchFileException e) {
			throw new IllegalStateException(location + " does not exist, from " + Path.of("").toAbsolutePath(), e);
		} catch (IOException e) {
			throw new UncheckedIOException("failed to read " + location, e);
		}
	}

	/** The rest of {@code in}, which is not closed: whoever opened it closes it. */
	public static String read(InputStream in) {
		try {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("failed to read the script's stream", e);
		}
	}
}
