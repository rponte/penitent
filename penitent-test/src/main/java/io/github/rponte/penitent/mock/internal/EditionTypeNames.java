package io.github.rponte.penitent.mock.internal;

import java.sql.Connection;
import java.sql.SQLException;

import io.github.rponte.penitent.internal.binding.TypeNameIntegration;
import io.github.rponte.penitent.internal.metadata.CompositeType;

/**
 * In the test's edition, a {@code %ROWTYPE} record goes by the name of a subtype of it, compiled in the edition: the
 * driver cannot resolve {@code "SCHEMA".TABLE%ROWTYPE} in a child edition of a schema with editions enabled, and
 * fails with {@code ORA-17060}, while it resolves a package subtype. Every other name, and every name outside the
 * test's edition, stays as it is.
 */
public final class EditionTypeNames implements TypeNameIntegration {

	@Override
	public String compositeName(Connection connection, CompositeType type, String name) throws SQLException {
		if (type.kind() != CompositeType.Kind.ROWTYPE || !connection.isWrapperFor(RowTypes.class)) {
			return name;
		}
		return connection.unwrap(RowTypes.class).nameOf(type, name);
	}

	/** A collection of {@code %ROWTYPE} records resolves by its own name: only its elements need the subtype. */
	@Override
	public String collectionName(Connection connection, String name) {
		return name;
	}
}
