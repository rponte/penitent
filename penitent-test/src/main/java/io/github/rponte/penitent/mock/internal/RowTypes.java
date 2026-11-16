package io.github.rponte.penitent.mock.internal;

import io.github.rponte.penitent.internal.metadata.CompositeType;

/**
 * What a connection in the test's edition answers to {@code unwrap}: the names its {@code %ROWTYPE} records go by
 * there. See {@link EditionTypeNames}.
 */
interface RowTypes {

	/** The name the driver can resolve for the {@code %ROWTYPE} {@code type}, which is {@code name} so far. */
	String nameOf(CompositeType type, String name);
}
