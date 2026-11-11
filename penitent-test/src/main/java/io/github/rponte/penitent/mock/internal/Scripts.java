package io.github.rponte.penitent.mock.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

/**
 * The prerequisite scripts shipped in this module, applied as a migration tool would: a statement ends with a
 * semicolon, a PL/SQL unit with a line holding only a slash. Applying them again is harmless: what already exists
 * is left as it is.
 */
public final class Scripts {

	public static final String ADMIN = "penitent-mock-admin.sql";
	public static final String SCHEMA = "penitent-mock-schema.sql";

	/** The name is already used (ORA-00955): the script ran before. */
	private static final Set<Integer> ALREADY_APPLIED = Set.of(955);

	private Scripts() {
	}

	public static void apply(String script, DataSource dataSource, String schema) {
		List<String> statements = statementsOf(read(script).replace("${schema}", schema));
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				try {
					statement.execute(sql);
				} catch (SQLException e) {
					if (!ALREADY_APPLIED.contains(e.getErrorCode())) {
						throw new IllegalStateException("failed to apply " + script + ": " + e.getMessage() + "\n" + sql, e);
					}
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to apply " + script + ": " + e.getMessage(), e);
		}
	}

	static List<String> statementsOf(String script) {
		List<String> statements = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean plsql = false;
		for (String line : script.lines().toList()) {
			String trimmed = line.trim();
			if (current.isEmpty() && (trimmed.isEmpty() || trimmed.startsWith("--"))) {
				continue;
			}
			if (current.isEmpty()) {
				plsql = trimmed.toUpperCase(Locale.ROOT).matches("CREATE( OR REPLACE)? (PACKAGE|PROCEDURE|FUNCTION|TYPE|TRIGGER)\\b.*")
						|| trimmed.toUpperCase(Locale.ROOT).matches("(BEGIN|DECLARE)\\b.*");
			}
			if (plsql && trimmed.equals("/")) {
				statements.add(current.toString().strip());
				current.setLength(0);
				continue;
			}
			current.append(line).append('\n');
			if (!plsql && trimmed.endsWith(";")) {
				String sql = current.toString().strip();
				statements.add(sql.substring(0, sql.length() - 1));
				current.setLength(0);
			}
		}
		if (!current.toString().isBlank()) {
			statements.add(current.toString().strip());
		}
		return statements;
	}

	private static String read(String script) {
		try (InputStream in = Scripts.class.getResourceAsStream("/io/github/rponte/penitent/mock/" + script)) {
			if (in == null) {
				throw new IllegalStateException(script + " is missing from penitent-test");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalStateException("failed to read " + script, e);
		}
	}
}
