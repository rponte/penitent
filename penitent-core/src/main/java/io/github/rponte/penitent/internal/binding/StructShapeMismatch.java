package io.github.rponte.penitent.internal.binding;

import java.sql.SQLException;
import java.util.List;

/**
 * A {@code Struct} that came back with another number of attributes than the proxy described its type with (#183):
 * the type changed in the database since, or the database or the driver still holds its old shape, as after its table
 * or the type itself changed while the application ran (#184, B1). Read by the binding's positions, it would run past
 * its end, or put values in the wrong properties without an error; so it is not read. The call that met it clears the
 * proxy's types, so that the next one reads them again.
 */
public final class StructShapeMismatch extends SQLException {

	private static final long serialVersionUID = 1L;

	StructShapeMismatch(String type, int received, List<String> described) {
		super(type + " came back with " + received + (received == 1 ? " attribute" : " attributes")
				+ ", but this proxy described it with " + described.size() + ": " + described + ", so the value was not"
				+ " read: the routine ran, and it is its result that could not be read. Either the type changed in the"
				+ " database since this proxy described it, and the next call reads it again; or the database or the"
				+ " driver still holds the type's old shape, as after its table or the type itself changed while the"
				+ " application ran, and a new connection may get it too: see \"Changing PL/SQL while the application"
				+ " runs\" in the documentation");
	}
}
