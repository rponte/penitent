package io.github.rponte.penitent.convert;

import java.util.function.Function;

import io.github.rponte.penitent.internal.convert.TypedConverter;

/**
 * Converts a value between the type Java declares and a type the library already knows how to send
 * to the database.
 *
 * <p>It applies wherever there is a scalar value: parameter, return value, {@code OUT}, attribute,
 * cursor column and anonymous block. In {@code List<J>} and {@code J[]}, it applies per element. Use
 * {@link PlsqlConvert} at a specific point, or {@link PlsqlConverters} for the type across the whole
 * interface.
 *
 * <p><b>{@code null} also goes through the converter</b>, because in a legacy database a {@code NULL}
 * often means something. If you do not need that, use {@link #ofNullable}. A whole collection that is
 * null does not go through it: it stays what it was.
 *
 * <p>What the library validates against the dictionary is {@code D}: a {@code D} that would lose the
 * time zone is still rejected, and {@code D} is what picks the version of an overloaded routine.
 *
 * @param <J> the type Java declares
 * @param <D> a scalar type that would work without a converter, such as {@code String},
 *        {@code BigDecimal} or {@code LocalDate}. The library carries it to JDBC, so it does not need
 *        to be a JDBC type. Prefer {@code java.time} over {@code java.sql}, which only exists for
 *        legacy code
 */
public interface PlsqlConverter<J, D> {

	D toDatabase(J value);

	J fromDatabase(D value);

	/** The functions receive {@code null} and decide what it means. */
	static <J, D> PlsqlConverter<J, D> of(Class<J> javaType, Class<D> databaseType,
			Function<? super J, ? extends D> toDatabase, Function<? super D, ? extends J> fromDatabase) {
		return new TypedConverter<>(javaType, databaseType, toDatabase, fromDatabase);
	}

	/** The functions never receive {@code null}, which stays {@code null} in both directions. */
	static <J, D> PlsqlConverter<J, D> ofNullable(Class<J> javaType, Class<D> databaseType,
			Function<? super J, ? extends D> toDatabase, Function<? super D, ? extends J> fromDatabase) {
		return of(javaType, databaseType,
				value -> value == null ? null : toDatabase.apply(value),
				value -> value == null ? null : fromDatabase.apply(value));
	}

	/**
	 * An enum stored as a code, such as {@code 'A'} for {@code Status.ACTIVE}, instead of its name. See
	 * {@link PlsqlEnumCodeConverter}.
	 */
	static <E extends Enum<E>, D> PlsqlEnumCodeConverter<E, D> enumByCode(Class<E> enumType, Class<D> codeType,
			Function<? super E, ? extends D> codeOf) {
		return new PlsqlEnumCodeConverter<>(enumType, codeType, codeOf);
	}
}
