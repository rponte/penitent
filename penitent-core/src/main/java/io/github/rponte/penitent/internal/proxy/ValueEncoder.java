package io.github.rponte.penitent.internal.proxy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.binding.OracleArrays;
import io.github.rponte.penitent.internal.binding.TemporaryLobs;
import io.github.rponte.penitent.internal.binding.TypeNameIntegration;
import io.github.rponte.penitent.internal.convert.OracleNumberLimits;
import io.github.rponte.penitent.internal.convert.OracleTimeZoneCodec;
import io.github.rponte.penitent.internal.convert.ScalarCodec;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;
import io.github.rponte.penitent.internal.metadata.CollectionType;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/** Java -> JDBC: the value as the driver has to receive it, a {@code Struct}, an {@code Array} or a scalar. */
final class ValueEncoder {

	private final TypeCache types;

	ValueEncoder(TypeCache types) {
		this.types = types;
	}

	/**
	 * As {@link #encode}, without the temporary LOB that a call binds a text or bytes for a {@code CLOB} or a
	 * {@code BLOB} through: for a tool that writes the value elsewhere, and not to the driver.
	 */
	Object encodeForWriting(Object value, Argument target, PlsqlBoolean literals, String schema, Connection connection)
			throws SQLException {
		if (value == null || target.isStruct() || target.isCollection()) {
			return encode(value, target, literals, schema, connection);
		}
		if (value instanceof Boolean flag) {
			return encodeBoolean(flag, target, literals);
		}
		requireFinite(value, target);
		requireWithinNumber(value, target);
		return encodeScalar(value, target);
	}

	Object encode(Object value, Argument target, PlsqlBoolean literals, String schema, Connection connection)
			throws SQLException {
		requireFinite(value, target);

		if (value == null) {
			return null;
		}
		if (target.isStruct()) {
			return types.binding(value.getClass(), target.compositeType(schema), connection).toStruct(value, connection);
		}
		if (target.isCollection()) {
			return encodeCollection(value, types.collectionType(target, schema, connection), literals, connection);
		}
		if (value instanceof Boolean flag) {
			return encodeBoolean(flag, target, literals);
		}
		requireWithinNumber(value, target);
		return TemporaryLobs.forLob(target.dataType(), encodeScalar(value, target), connection);
	}

	/**
	 * A number beyond {@code NUMBER}'s range, for a type that holds no more than a {@code NUMBER}: refused here, where
	 * the parameter is known, rather than as each driver's own error while binding (see {@code OracleNumberLimits}). A
	 * {@code BINARY_DOUBLE} or a {@code BINARY_FLOAT} holds it.
	 */
	private static void requireWithinNumber(Object value, Argument target) {
		if (binaryFloatingPoint(target) || target.isStruct() || target.isCollection()) {
			return;
		}
		try {
			OracleNumberLimits.requireWithinIfNumber(value);
		} catch (ScalarCodec.ConversionException e) {
			throw new PlsqlMappingException((target.name() == null ? "the value it returns" : target.name()) + " is "
					+ target.dataType() + ": " + e.getMessage(), e);
		}
	}

	/** A scalar, whose refusal, such as a date beyond Oracle's range, names the parameter as {@code requireFinite} does. */
	private static Object encodeScalar(Object value, Argument target) {
		try {
			return ScalarCodec.encode(value, ValueConversion.DEFAULT);
		} catch (ScalarCodec.ConversionException e) {
			throw new PlsqlMappingException((target.name() == null ? "the value it returns" : target.name()) + " is "
					+ target.dataType() + ": " + e.getMessage(), e);
		}
	}

	/** {@code BINARY_DOUBLE} or {@code BINARY_FLOAT}: the types that hold {@code NaN} and the infinities. */
	static boolean binaryFloatingPoint(Argument target) {
		return "BINARY_DOUBLE".equals(target.dataType()) || "BINARY_FLOAT".equals(target.dataType());
	}

	/**
	 * {@code NaN} or an infinity, for a type that does not hold it, such as a {@code NUMBER}: refused here, where the
	 * parameter is known, rather than as the driver's {@code IllegalArgumentException} or {@code NumberFormatException}.
	 */
	private static void requireFinite(Object value, Argument target) {
		boolean finite = value instanceof Double doubleValue ? Double.isFinite(doubleValue)
				: !(value instanceof Float floatValue) || Float.isFinite(floatValue);
		if (!finite && !binaryFloatingPoint(target) && !target.isStruct() && !target.isCollection()) {
			throw new PlsqlMappingException((target.name() == null ? "the value it returns" : target.name()) + " is "
					+ target.dataType() + ", which holds no NaN nor infinity, and the value is " + value
					+ ": declare the parameter BINARY_DOUBLE or BINARY_FLOAT, or send a finite value");
		}
	}

	/**
	 * A dictionary BOOLEAN has no literals; next comes whatever was declared, and finally the
	 * type's default. For a NUMBER the literal goes as a number: as text, Oracle could resolve an
	 * overload to the VARCHAR2 version instead of the one chosen here.
	 */
	private static Object encodeBoolean(Boolean flag, Argument target, PlsqlBoolean literals) {
		if (target.isBoolean()) {
			return flag;
		}
		if (target.isNumeric()) {
			if (literals == null) {
				return flag ? 1 : 0;
			}
			String literal = flag ? literals.trueValue() : literals.falseValue();
			try {
				return new java.math.BigDecimal(literal);
			} catch (NumberFormatException e) {
				throw new PlsqlMappingException(target.name() + " is " + target.dataType()
						+ ", and the literal '" + literal + "' of @PlsqlBoolean is not a number", e);
			}
		}
		return ScalarCodec.encode(flag, ValueConversion.from(literals));
	}

	/**
	 * The collection's name and the element's are two distinct Oracle identifiers, and both
	 * come from the dictionary. Because it did not derive them, the old code had to declare both
	 * by hand on every call. An element that is itself a collection is encoded the same way, one
	 * level down.
	 */
	Object encodeCollection(Object value, CollectionType type, PlsqlBoolean literals, Connection connection)
			throws SQLException {

		Object[] elements = toArray(value);
		Object[] encoded = new Object[elements.length];

		for (int i = 0; i < elements.length; i++) {
			Object element = elements[i];
			if (element == null) {
				encoded[i] = null;
			} else if (type.compositeElement() != null) {
				encoded[i] = types.binding(element.getClass(), type.compositeElement(), connection).toStruct(element,
						connection);
			} else if (type.collectionElement() != null) {
				if (!(element instanceof Collection<?>) && !element.getClass().isArray()) {
					throw new PlsqlMappingException("an element of " + type.bindName() + " is a collection ("
							+ type.collectionElement().bindName() + "), but the Java element is "
							+ element.getClass().getName() + "; send a List or an array");
				}
				encoded[i] = encodeCollection(element, type.collectionElement(), literals, connection);
			} else {
				// a TIMESTAMP WITH LOCAL TIME ZONE element is built by the library: see OracleTimeZoneCodec
				encoded[i] = OracleTimeZoneCodec.forComposite(type.scalarElement(),
						encodeElement(element, i, type, literals), connection);
			}
		}
		return OracleArrays.create(connection, TypeNameIntegration.collectionNameOf(connection, type.bindName()),
				encoded);
	}

	/**
	 * An element, whose refusal names its position, counted from 1: a date beyond Oracle's range, or a number beyond
	 * {@code NUMBER}'s, unless the element is a {@code BINARY_DOUBLE} or a {@code BINARY_FLOAT}.
	 */
	private static Object encodeElement(Object element, int index, CollectionType type, PlsqlBoolean literals) {
		try {
			Object encoded = ScalarCodec.encode(element, ValueConversion.from(literals));
			if (!OracleNumberLimits.isBinaryFloatingPoint(type.scalarElement())) {
				OracleNumberLimits.requireWithinIfNumber(encoded);
			}
			return encoded;
		} catch (ScalarCodec.ConversionException e) {
			throw new PlsqlMappingException("element " + (index + 1) + " of " + type.bindName() + ": " + e.getMessage(),
					e);
		}
	}

	static Object[] toArray(Object value) {
		if (value instanceof Object[] array) {
			return array;
		}
		if (value instanceof Collection<?> collection) {
			return collection.toArray();
		}
		if (value.getClass().isArray()) {
			int length = java.lang.reflect.Array.getLength(value);
			Object[] boxed = new Object[length];
			for (int i = 0; i < length; i++) {
				boxed[i] = java.lang.reflect.Array.get(value, i);
			}
			return boxed;
		}
		throw new PlsqlMappingException("expected an array or Collection for a collection parameter, got "
				+ value.getClass().getName());
	}
}
