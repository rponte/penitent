package io.github.rponte.penitent.mock.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a script may apply in the test's edition: only objects the edition keeps a version of, so that nothing of the
 * script reaches the database outside it, and they go with it. Every statement is checked before any runs.
 */
public final class EditionScripts {

	/** The types the edition can hold, among those a test would replace. */
	static final List<String> ACCEPTED = List.of("VIEW", "PACKAGE", "PACKAGE BODY", "FUNCTION", "PROCEDURE", "SYNONYM");

	private EditionScripts() {
	}

	/**
	 * Refuses, before anything runs, a statement that is not {@code CREATE OR REPLACE} of one of the {@link #ACCEPTED}
	 * types: a table, DML, {@code DROP} or {@code ALTER} would change the database itself, and an object type or a
	 * trigger changes what real data is or does.
	 */
	public static void requireAccepted(List<PlsqlScript.Statement> statements) {
		for (PlsqlScript.Statement statement : statements) {
			if (!statement.orReplace() || !ACCEPTED.contains(statement.type())) {
				throw new IllegalStateException(statement.where() + ": " + statement.preview() + " cannot be applied in"
						+ " the test's edition, which keeps versions only of what CREATE OR REPLACE makes of a VIEW,"
						+ " PACKAGE, PACKAGE BODY, FUNCTION, PROCEDURE or SYNONYM: anything else would reach the"
						+ " database itself");
			}
		}
	}

	/**
	 * Refuses an object of another schema, whose editions cannot be read without a DBA, and a type {@code schema} does
	 * not edition: in such a schema, the statement would replace the real object, for every session, for good.
	 */
	public static void requireEditioned(List<PlsqlScript.Statement> statements, String schema, Connection connection)
			throws SQLException {
		Set<String> editioned = editionedTypes(connection);
		for (PlsqlScript.Statement statement : statements) {
			if (statement.owner() != null && !statement.owner().equals(schema)) {
				throw new IllegalStateException(statement.where() + ": " + statement.object() + " is of the schema "
						+ statement.owner() + ", and a script applies only to the schema of the mocks, " + schema
						+ ", whose editions can be checked");
			}
			if (!editioned.contains(statement.type())) {
				String type = statement.type().equals("PACKAGE BODY") ? "PACKAGE" : statement.type();
				throw new IllegalStateException(statement.where() + ": " + statement.object() + " would replace the real "
						+ statement.type() + " for every session, for good, since " + schema + " does not edition its "
						+ statement.type() + " objects. On a test database, a DBA can run ALTER USER " + schema
						+ " ENABLE EDITIONS FOR " + type + ", which cannot be undone; it fails with ORA-38819 when an"
						+ " object without editions, such as an object type, a table or a materialized view, depends on"
						+ " one of them, and FORCE would leave that object INVALID");
			}
		}
	}

	private static Set<String> editionedTypes(Connection connection) throws SQLException {
		Set<String> types = new HashSet<>();
		try (PreparedStatement select = connection.prepareStatement("SELECT OBJECT_TYPE FROM USER_EDITIONED_TYPES");
				ResultSet rows = select.executeQuery()) {
			while (rows.next()) {
				types.add(rows.getString(1));
			}
		}
		return types;
	}
}
