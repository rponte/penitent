package io.github.rponte.penitent.internal.convert;

import java.util.function.Function;

import io.github.rponte.penitent.convert.PlsqlConverter;

/** A converter made of functions: the types are declared, because they cannot be read from a lambda. */
public record TypedConverter<J, D>(Class<J> javaType, Class<D> databaseType, Function<? super J, ? extends D> to,
		Function<? super D, ? extends J> from) implements PlsqlConverter<J, D>, DeclaredTypes {

	@Override
	public D toDatabase(J value) {
		return to.apply(value);
	}

	@Override
	public J fromDatabase(D value) {
		return from.apply(value);
	}
}
