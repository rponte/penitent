package io.github.rponte.penitent.internal.convert;

import io.github.rponte.penitent.convert.PlsqlConverter;

/**
 * A conversion the library ships with, for a type that needs neither a connection nor the
 * dictionary. Whatever needs them lives in {@link ScalarCodec} and in the bindings.
 *
 * <p>The difference from {@link PlsqlConverter} is the read side: here the value arrives as the
 * driver delivered it, not as a ready-made {@code D}, because the same Oracle type may come in
 * different classes. These conversions are what a {@code D} relies on.
 *
 * <p>{@code null} never reaches this point: {@link ScalarCodec} handles it.
 *
 * @param <J> the type Java declares
 */
interface BuiltInConverter<J> {

	/** The default is to hand the value to the driver as is, which is what it already accepts. */
	default Object toDatabase(J value) {
		return value;
	}

	J fromDatabase(Object raw);
}
