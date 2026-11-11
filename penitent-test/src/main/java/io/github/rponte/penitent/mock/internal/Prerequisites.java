package io.github.rponte.penitent.mock.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * What the schema needs before a test can mock, checked all at once, so that the message lists everything that is
 * missing, with the scripts that provide it. The library never applies them by itself: {@code ENABLE EDITIONS}
 * cannot be undone.
 */
final class Prerequisites {

	private Prerequisites() {
	}

	static void require(Connection connection, int schemaVersion) throws SQLException {
		String schema = value(connection, "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL");
		List<String> missing = new ArrayList<>();
		// the schema's own view of it: DBA_USERS.EDITIONS_ENABLED needs a DBA to be read
		List<String> editioned = values(connection, "SELECT OBJECT_TYPE FROM USER_EDITIONED_TYPES");
		if (!editioned.contains("PACKAGE") || !editioned.contains("PACKAGE BODY")) {
			missing.add("editions are not enabled for " + schema + " packages, only for " + editioned
					+ " (penitent-mock-admin.sql)");
		}
		List<String> privileges = values(connection, "SELECT PRIVILEGE FROM SESSION_PRIVS"
				+ " WHERE PRIVILEGE IN ('CREATE ANY EDITION', 'DROP ANY EDITION')");
		for (String privilege : List.of("CREATE ANY EDITION", "DROP ANY EDITION")) {
			if (!privileges.contains(privilege)) {
				missing.add(schema + " lacks the privilege " + privilege + " (penitent-mock-admin.sql)");
			}
		}
		Integer version = schemaVersion(connection);
		if (version == null) {
			missing.add("the package PENITENT_MOCK and the table PENITENT_MOCK_CALLS are missing, or do not compile"
					+ " (penitent-mock-schema.sql)");
		} else if (version != schemaVersion) {
			missing.add("PENITENT_MOCK is version " + version + ", and this library expects version " + schemaVersion
					+ ": apply penitent-mock-schema.sql again");
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException("the schema " + schema + " is not ready for mocks:\n  - "
					+ String.join("\n  - ", missing) + "\nApply penitent-mock-admin.sql, as a DBA, and"
					+ " penitent-mock-schema.sql, as " + schema + ": both are in penitent-test, under"
					+ " io/github/rponte/penitent/mock/. On a disposable database, PlsqlMocks.prepare applies them.");
		}
	}

	private static Integer schemaVersion(Connection connection) {
		try {
			return Integer.valueOf(value(connection, "SELECT PENITENT_MOCK.VERSION FROM DUAL"));
		} catch (SQLException | NumberFormatException e) {
			return null;
		}
	}

	private static String value(Connection connection, String sql) throws SQLException {
		List<String> values = values(connection, sql);
		return values.isEmpty() ? null : values.get(0);
	}

	private static List<String> values(Connection connection, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}
}
