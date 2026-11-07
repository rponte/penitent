package io.github.rponte.penitent.internal.convert;

import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlEnumCodeConverter;

/**
 * A converter applied at a point: a declared value, or the elements of a collection. A failure inside
 * the converter comes out with the value and the converter's name, so that whoever knows the point
 * can add the rest.
 *
 * @param declared the type Java declares at the point, which may be a primitive
 * @param databaseType the {@code D}, which is what the library converts and validates
 * @param elementWise whether it applies to the elements rather than to the value
 */
public record AppliedConverter(PlsqlConverter<Object, Object> converter, Class<?> declared, Class<?> databaseType,
		boolean elementWise) {

	public Object toDatabase(Object value) {
		try {
			return converter.toDatabase(value);
		} catch (RuntimeException e) {
			throw failure("write", value, e);
		}
	}

	public Object fromDatabase(Object value) {
		Object converted;
		try {
			converted = converter.fromDatabase(value);
		} catch (RuntimeException e) {
			throw failure("read", value, e);
		}
		if (converted == null && declared.isPrimitive()) {
			throw new ScalarCodec.ConversionException(name() + " returned null for a primitive " + declared.getName()
					+ "; declare the wrapper type");
		}
		return converted;
	}

	private ScalarCodec.ConversionException failure(String direction, Object value, RuntimeException cause) {
		return new ScalarCodec.ConversionException(name() + " failed to " + direction + " "
				+ (value == null ? "NULL" : "'" + value + "'") + ": " + cause, cause);
	}

	/** A converter made by a factory has no class of its own to name, so it is named by its type. */
	private String name() {
		boolean fromFactory = converter instanceof TypedConverter<?, ?>
				|| converter.getClass() == PlsqlEnumCodeConverter.class;
		return fromFactory && converter instanceof DeclaredTypes declared
				? "the converter of " + declared.javaType().getSimpleName()
				: converter.getClass().getSimpleName();
	}
}
