package io.github.rponte.penitent.convert;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.DeclaredTypes;

/**
 * Maps an enum to a code stored in the database, such as {@code 'A'} for {@code Status.ACTIVE} or
 * {@code 2} for {@code Priority.HIGH}, instead of the constant's name.
 *
 * <pre>{@code
 * // for the type across the whole interface
 * PlsqlConverters.of(PlsqlConverter.enumByCode(Status.class, String.class, Status::code));
 *
 * // for @PlsqlConvert, which needs a class with a no-arg constructor
 * public class StatusConverter extends PlsqlEnumCodeConverter<Status, String> {
 *     public StatusConverter() {
 *         super(Status.class, String.class, Status::code);
 *     }
 * }
 * }</pre>
 *
 * <p>It is strict: two constants with the same code, or a constant without a code, fail when the
 * converter is created, and reading a code that no constant has is an error that lists the known
 * codes. {@code NULL} stays {@code null} in both directions; to give it a meaning, write a converter
 * with {@link PlsqlConverter#of}.
 *
 * <p>Codes are compared as the database returns them: a text code ignores trailing spaces, so a
 * {@code CHAR(3)} column with {@code 'A  '} reads as {@code 'A'}, and a {@link BigDecimal} code
 * ignores its scale, so {@code 1.00} reads as {@code 1}.
 *
 * @param <E> the enum
 * @param <D> the type of the code, such as {@code String}, {@code Integer} or {@code BigDecimal}
 */
public class PlsqlEnumCodeConverter<E extends Enum<E>, D> implements PlsqlConverter<E, D>, DeclaredTypes {

	private final Class<E> enumType;
	private final Class<D> codeType;
	private final Function<? super E, ? extends D> codeOf;
	private final Map<Object, E> byCode = new LinkedHashMap<>();

	public PlsqlEnumCodeConverter(Class<E> enumType, Class<D> codeType, Function<? super E, ? extends D> codeOf) {
		this.enumType = Objects.requireNonNull(enumType, "enumType");
		this.codeType = Objects.requireNonNull(codeType, "codeType");
		this.codeOf = Objects.requireNonNull(codeOf, "codeOf");

		for (E constant : enumType.getEnumConstants()) {
			D code = codeOf.apply(constant);
			if (code == null) {
				throw new PlsqlMappingException(enumType.getSimpleName() + "." + constant.name() + " has no code");
			}
			E other = byCode.putIfAbsent(key(code), constant);
			if (other != null) {
				throw new PlsqlMappingException(enumType.getSimpleName() + "." + other.name() + " and "
						+ enumType.getSimpleName() + "." + constant.name() + " share the code " + quoted(code));
			}
		}
	}

	@Override
	public D toDatabase(E value) {
		return value == null ? null : codeOf.apply(value);
	}

	@Override
	public E fromDatabase(D value) {
		if (value == null) {
			return null;
		}
		E constant = byCode.get(key(value));
		if (constant == null) {
			throw new IllegalArgumentException("no " + enumType.getSimpleName() + " has the code " + quoted(value)
					+ "; known codes: " + byCode.keySet());
		}
		return constant;
	}

	@Override
	public Class<E> javaType() {
		return enumType;
	}

	@Override
	public Class<D> databaseType() {
		return codeType;
	}

	/** How the database may return the same code in another form: padded, or with another scale. */
	private static Object key(Object code) {
		if (code instanceof String text) {
			return text.stripTrailing();
		}
		if (code instanceof BigDecimal number) {
			return number.signum() == 0 ? BigDecimal.ZERO : number.stripTrailingZeros();
		}
		return code;
	}

	private static String quoted(Object code) {
		return code instanceof String ? "'" + code + "'" : String.valueOf(code);
	}
}
