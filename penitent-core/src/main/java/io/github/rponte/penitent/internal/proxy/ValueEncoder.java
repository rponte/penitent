package io.github.rponte.penitent.internal.proxy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.binding.OracleArrays;
import io.github.rponte.penitent.internal.binding.TemporaryLobs;
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

	Object encode(Object value, Argument target, PlsqlBoolean literals, String schema, Connection connection)
			throws SQLException {

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
		return TemporaryLobs.forLob(target.dataType(), ScalarCodec.encode(value, ValueConversion.DEFAULT),
				connection);
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
						ScalarCodec.encode(element, ValueConversion.from(literals)), connection);
			}
		}
		return OracleArrays.create(connection, type.bindName(), encoded);
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
