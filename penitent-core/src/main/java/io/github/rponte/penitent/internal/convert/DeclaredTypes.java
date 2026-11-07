package io.github.rponte.penitent.internal.convert;

/** A converter that states its {@code J} and {@code D}, because they cannot be read from its class. */
public interface DeclaredTypes {

	Class<?> javaType();

	Class<?> databaseType();
}
