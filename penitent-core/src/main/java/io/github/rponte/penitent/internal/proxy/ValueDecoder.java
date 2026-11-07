package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.ParameterizedType;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.binding.OracleArrays;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.ScalarCodec;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;
import io.github.rponte.penitent.internal.log.CallLog;
import io.github.rponte.penitent.internal.metadata.CollectionType;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/** JDBC -> Java: what the database returned, as the {@link JavaTarget} declares it. */
final class ValueDecoder {

	private final TypeCache types;

	ValueDecoder(TypeCache types) {
		this.types = types;
	}

	/**
	 * Composites and collections are logged as they came from the database, which is what a
	 * JavaBean's {@code toString} does not show. Scalars and cursors are logged as Java receives
	 * them: a cursor cannot be read twice.
	 */
	Object decodeValue(CallableStatement statement, int position, Argument argument, JavaTarget target,
			RoutineMetadata routine, Connection connection, CallLog log, String outName) throws SQLException {

		if (argument.isStruct()) {
			Struct struct = (Struct) statement.getObject(position);
			received(log, outName, struct);
			return struct == null ? null
					: types.binding(target.type(), argument.compositeType(routine.schema()), connection)
							.fromStruct(struct, connection);
		}
		if (argument.isCollection()) {
			return decodeCollection(statement, position, argument, target, routine, connection, log, outName);
		}
		Object value;
		if (argument.isCursor()) {
			value = decodeCursor((java.sql.ResultSet) statement.getObject(position), target, routine.qualifiedName(),
					connection);
		} else {
			AppliedConverter applied = target.converter();
			value = applied == null
					? ScalarCodec.decode(ScalarCodec.read(statement, position, target.type()), target.type(),
							target.conversion(), connection)
					: applied.fromDatabase(ScalarCodec.decode(ScalarCodec.read(statement, position,
							applied.databaseType()), applied.databaseType(), target.conversion(), connection));
		}
		received(log, outName, value);
		return value;
	}

	/** A null {@code outName} means the function's return value. */
	private static void received(CallLog log, String outName, Object value) {
		if (outName == null) {
			log.result(value);
		} else {
			log.out(outName, value);
		}
	}

	/**
	 * A cursor becomes a {@code List<T>} without {@code @SuppressWarnings}: {@code T} comes from
	 * the declared generic type, not from a cast the compiler cannot check. A per-element converter
	 * makes it a cursor of that scalar.
	 *
	 * @param source what returned the cursor, for the message
	 */
	Object decodeCursor(java.sql.ResultSet cursor, JavaTarget target, String source, Connection connection)
			throws SQLException {

		requireList(target, source, "a cursor");
		Class<?> elementType = elementTypeOf(target);
		AppliedConverter applied = target.converter() != null && target.converter().elementWise()
				? target.converter() : null;

		try (java.sql.ResultSet rs = cursor) {
			if (rs == null) {
				return List.of();
			}
			return types.cursor(elementType).readAll(rs, connection, target.conversion(), applied);
		}
	}

	/** The element type comes from the declared {@code List<T>}, not from a cast at the end. */
	private Object decodeCollection(CallableStatement statement, int position, Argument argument,
			JavaTarget target, RoutineMetadata routine, Connection connection, CallLog log, String outName)
			throws SQLException {

		requireList(target, routine.qualifiedName(), "a collection");
		java.lang.reflect.Type javaElement = elementGenericOf(target);
		CollectionType type = types.collectionType(argument, routine.schema(), connection);
		AppliedConverter applied = target.converter();
		Class<?> received = applied != null ? applied.databaseType() : classOf(javaElement);

		Array array = (Array) statement.getObject(position);
		if (array == null) {
			received(log, outName, null);
			return List.of();
		}
		Object[] raw = OracleArrays.elements(array, received);
		received(log, outName, raw);
		return decodeElements(raw, type, javaElement, applied, target.conversion(), connection);
	}

	/**
	 * The elements of one level. An inner collection is a {@code List}, or an array if Java declares
	 * one, decoded with the element type of that level.
	 */
	Object decodeElements(Object[] raw, CollectionType type, java.lang.reflect.Type javaElement,
			AppliedConverter applied, ValueConversion conversion, Connection connection) throws SQLException {
		Class<?> javaClass = classOf(javaElement);
		List<Object> result = new ArrayList<>(raw.length);

		for (Object element : raw) {
			if (applied != null) {
				result.add(applied.fromDatabase(ScalarCodec.decode(element, applied.databaseType(), conversion,
						connection)));
			} else if (element == null) {
				result.add(null);
			} else if (type.collectionElement() != null && element instanceof Array inner) {
				java.lang.reflect.Type innerElement = RoutineMetadata.elementTypeOf(javaElement);
				List<?> decoded = (List<?>) decodeElements(OracleArrays.elements(inner, classOf(innerElement)),
						type.collectionElement(), innerElement, null, conversion, connection);
				result.add(javaClass.isArray() ? toJavaArray(decoded, javaClass.getComponentType()) : decoded);
			} else if (element instanceof Struct struct) {
				result.add(types.binding(javaClass, type.compositeElement(), connection).fromStruct(struct, connection));
			} else {
				result.add(ScalarCodec.decode(element, javaClass, conversion, connection));
			}
		}
		return result;
	}

	private static Object toJavaArray(List<?> values, Class<?> component) {
		Object array = java.lang.reflect.Array.newInstance(component, values.size());
		for (int i = 0; i < values.size(); i++) {
			java.lang.reflect.Array.set(array, i, values.get(i));
		}
		return array;
	}

	/** @param source what returns the value: the routine, or the block's {@code :result} */
	static void requireList(JavaTarget target, String source, String what) {
		if (!List.class.isAssignableFrom(target.type())) {
			throw new PlsqlMappingException(target.description() + " -> " + source
					+ " returns " + what + "; declare List<T>, not " + target.type().getSimpleName());
		}
	}

	private static Class<?> elementTypeOf(JavaTarget target) {
		if (target.generic() instanceof ParameterizedType parameterized
				&& parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
			return element;
		}
		throw new PlsqlMappingException(target.description()
				+ " returns a List without an element type; declare List<SomeType>");
	}

	/** Like {@link #elementTypeOf(JavaTarget)}, but the element may itself be generic, as in {@code List<List<String>>}. */
	static java.lang.reflect.Type elementGenericOf(JavaTarget target) {
		if (target.generic() instanceof ParameterizedType parameterized
				&& (parameterized.getActualTypeArguments()[0] instanceof Class<?>
						|| parameterized.getActualTypeArguments()[0] instanceof ParameterizedType)) {
			return parameterized.getActualTypeArguments()[0];
		}
		throw new PlsqlMappingException(target.description()
				+ " returns a List without an element type; declare List<SomeType>");
	}

	static Class<?> classOf(java.lang.reflect.Type type) {
		if (type instanceof ParameterizedType parameterized) {
			return (Class<?>) parameterized.getRawType();
		}
		return type instanceof Class<?> c ? c : Object.class;
	}
}
