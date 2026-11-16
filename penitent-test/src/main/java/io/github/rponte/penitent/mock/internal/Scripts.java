package io.github.rponte.penitent.mock.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
		List<String> statements = PlsqlScript.parse(read(script).replace("${schema}", schema), script).stream()
				.map(PlsqlScript.Statement::sql).toList();
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
