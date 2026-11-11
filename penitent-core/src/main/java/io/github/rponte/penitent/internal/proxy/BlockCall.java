package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.binding.JavaProperties;
import io.github.rponte.penitent.internal.binding.OracleArrays;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.ConverterLookup;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;
import io.github.rponte.penitent.internal.convert.ScalarCodec;
import io.github.rponte.penitent.internal.log.CallLog;
import io.github.rponte.penitent.internal.metadata.BlockMetadata;
import io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure;
import io.github.rponte.penitent.internal.metadata.Dictionary;
import io.github.rponte.penitent.internal.metadata.ReturnType;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.metadata.RoutineValidation;

/**
 * Anonymous block: parameters by <b>name</b>, not by position, and no dictionary lookup, since a
 * block has no signature there. See {@link PlsqlBlock}.
 */
final class BlockCall {

	private final TypeCache types;
	private final ValueEncoder encoder;
	private final ValueDecoder decoder;

	/** Per instance, for the same reason as {@link TypeCache}. */
	private final Map<Method, BlockMetadata> blocks = new ConcurrentHashMap<>();

	BlockCall(TypeCache types, ValueEncoder encoder, ValueDecoder decoder) {
		this.types = types;
		this.encoder = encoder;
		this.decoder = decoder;
	}

	/**
	 * A block has no signature in the dictionary, so there is nothing to check against it: what can
	 * be checked before the call is the block itself, its placeholders against the method's parameters.
	 */
	void validate(Method method, PlsqlBlock block) {
		blocks.computeIfAbsent(method, m -> BlockMetadata.of(m, block));
	}

	Object call(Method method, PlsqlBlock block, Object[] args, Connection connection) {
		BlockMetadata metadata = blocks.computeIfAbsent(method, m -> BlockMetadata.of(m, block));

		Map<String, Object> byName = new java.util.LinkedHashMap<>();
		Map<String, java.lang.reflect.Parameter> parametersByName = new java.util.HashMap<>();
		java.lang.reflect.Parameter[] parameters = method.getParameters();
		for (int i = 0; i < parameters.length; i++) {
			String name = BlockMetadata.nameOf(method, parameters[i]);
			byName.put(name, args[i]);
			parametersByName.put(name, parameters[i]);
		}

		boolean expectsResult = ReturnType.of(method) != void.class;

		CallLog log = CallLog.block(method, metadata.sql());
		try {
			Object result = call(method, metadata, byName, parametersByName, expectsResult, connection, log);
			log.completed();
			return result;
		} catch (RuntimeException e) {
			log.failed(e);
			throw e;
		}
	}

	private Object call(Method method, BlockMetadata metadata, Map<String, Object> byName,
			Map<String, java.lang.reflect.Parameter> parametersByName, boolean expectsResult, Connection connection,
			CallLog log) {
		SessionTypes sessionTypes = new SessionTypes(connection);
		try (CallableStatement statement = connection.prepareCall(metadata.sql())) {

			List<String> placeholders = metadata.placeholders();
			for (int i = 0; i < placeholders.size(); i++) {
				int position = i + 1;
				if (position == metadata.resultPosition()) {
					registerResult(statement, position, method, metadata, sessionTypes);
				} else {
					bindParameter(statement, position, placeholders.get(i), byName.get(placeholders.get(i)),
							parametersByName.get(placeholders.get(i)), method, sessionTypes, connection, log);
				}
			}

			log.executing();
			Jdbc.execute(statement, metadata.sql());
			if (!expectsResult) {
				return null;
			}
			try {
				Object result = decodeResult(statement, method, metadata, sessionTypes, connection);
				log.result(result);
				return result;
			} catch (ScalarCodec.ConversionException e) {
				throw new PlsqlMappingException(e.getMessage(), e);
			} catch (SQLException e) {
				throw new SqlFailure(metadata.sql(), e, PlsqlFailure.Phase.RESULT);
			}
		} catch (SQLException e) {
			throw new SqlFailure(metadata.sql(), e, PlsqlFailure.Phase.PREPARATION);
		}
	}

	/**
	 * The types a block declares, resolved in the session's schema. The schema is read once per call,
	 * and only if the block declares a type; the resolution itself is cached.
	 */
	private final class SessionTypes {

		private final Connection connection;
		private String schema;

		private SessionTypes(Connection connection) {
			this.connection = connection;
		}

		BlockMetadata.DeclaredType of(String declared) {
			if (schema == null) {
				schema = new Dictionary(connection).currentSchema();
			}
			return types.declaredType(schema, declared, connection);
		}
	}

	/**
	 * A cursor needs no name; a collection or a composite needs the one in {@code resultType}; a
	 * scalar takes its SQL type from Java. Whatever does not fit fails here, before the block runs.
	 */
	private void registerResult(CallableStatement statement, int position, Method method,
			BlockMetadata metadata, SessionTypes sessionTypes) throws SQLException {
		if (metadata.cursorResult()) {
			statement.registerOutParameter(position, Types.REF_CURSOR);
			return;
		}
		if (metadata.resultType().isBlank()) {
			int sqlType = resultSqlType(method);
			if (sqlType == Jdbc.ORACLE_PLSQL_BOOLEAN) {
				// some versions read a null BOOLEAN wrong: see NullBooleanOutputs
				NullBooleanOutputs.warnIfMisread(method, CallLog.javaName(method) + " returns a BOOLEAN",
						statement.getConnection());
			}
			statement.registerOutParameter(position, sqlType, "");
			return;
		}
		BlockMetadata.DeclaredType declared = sessionTypes.of(metadata.resultType());
		Class<?> returned = ReturnType.of(method);
		if (declared.isCollection()) {
			ValueDecoder.requireList(JavaTarget.ofReturn(method, types.converters()), "the block",
					"a collection");
			String refusal = RoutineValidation.nestingRefusal(declared.collection(),
					RoutineMetadata.elementTypeOf(ReturnType.genericOf(method)));
			if (refusal != null) {
				throw new PlsqlMappingException(":result: " + refusal);
			}
		} else if (Collection.class.isAssignableFrom(returned) || returned.isArray()) {
			throw new PlsqlMappingException("resultType " + metadata.resultType()
					+ " is a composite type; declare its record or JavaBean, not " + returned.getSimpleName());
		}
		statement.registerOutParameter(position, declared.isCollection() ? Types.ARRAY : Types.STRUCT,
				declared.qualifiedBindName());
	}

	/**
	 * As with :result: without a dictionary, a Java boolean is a PL/SQL BOOLEAN, unless
	 * {@link PlsqlBoolean} declares the literals. A composite or a collection has to say its Oracle
	 * type with {@link PlsqlType}: the block has no signature to take it from.
	 */
	private void bindParameter(CallableStatement statement, int position, String name, Object declaredValue,
			java.lang.reflect.Parameter parameter, Method method, SessionTypes sessionTypes, Connection connection,
			CallLog log) throws SQLException {
		PlsqlBoolean literals = parameter.getAnnotation(PlsqlBoolean.class);
		AppliedConverter applied = scalarInBlock(ConverterLookup.find(types.converters(),
				parameter.getAnnotation(PlsqlConvert.class), parameter.getType(),
				RoutineMetadata.elementClassOf(parameter.getParameterizedType()), "parameter " + parameter.getName()), method);
		Object value = applied == null ? declaredValue : applied.toDatabase(declaredValue);
		Class<?> javaType = applied == null ? parameter.getType() : applied.databaseType();
		String typeName = declaredTypeOf(parameter, javaType);
		if (typeName != null) {
			bindDeclared(statement, position, name, value, sessionTypes.of(typeName), parameter, literals, method,
					connection, log);
			return;
		}
		requireNoDeclaredTypeNeeded(method, name, javaType);
		if (literals == null && (javaType == boolean.class || javaType == Boolean.class)) {
			log.sent(name, value);
			statement.setObject(position, value, Jdbc.ORACLE_PLSQL_BOOLEAN);
		} else {
			Object bindable = ScalarCodec.encode(value, ValueConversion.from(literals));
			log.sent(name, bindable);
			statement.setObject(position, ScalarCodec.forDriver(bindable));
		}
	}

	/** On the parameter or, for a composite, on its class. A collection is not named by its element's class. */
	private static String declaredTypeOf(java.lang.reflect.Parameter parameter, Class<?> javaType) {
		PlsqlType onParameter = parameter.getAnnotation(PlsqlType.class);
		if (onParameter != null) {
			return onParameter.value();
		}
		PlsqlType onClass = JavaProperties.isComposite(javaType) ? javaType.getAnnotation(PlsqlType.class) : null;
		return onClass == null ? null : onClass.value();
	}

	/** Sent as a scalar, a List or a JavaBean would reach Oracle as something it cannot read ({@code ORA-17004}). */
	private static void requireNoDeclaredTypeNeeded(Method method, String name, Class<?> javaType) {
		if (Collection.class.isAssignableFrom(javaType) || (javaType.isArray() && javaType != byte[].class)) {
			throw new PlsqlMappingException("parameter " + name + " is a "
					+ javaType.getSimpleName() + "; in a block, declare its Oracle type with @PlsqlType, as in"
					+ " @PlsqlType(\"NAME_LIST_T\")");
		}
		if (JavaProperties.isComposite(javaType)) {
			throw new PlsqlMappingException("parameter " + name + " is "
					+ javaType.getSimpleName() + ", a composite; in a block, declare its Oracle type with @PlsqlType,"
					+ " on the parameter or on " + javaType.getSimpleName() + ", as in @PlsqlType(\"ADDRESS_T\")");
		}
	}

	/** A null still needs the type's name: without it, Oracle does not know what the block receives. */
	private void bindDeclared(CallableStatement statement, int position, String name, Object value,
			BlockMetadata.DeclaredType type, java.lang.reflect.Parameter parameter, PlsqlBoolean literals,
			Method method, Connection connection, CallLog log) throws SQLException {
		boolean many = value instanceof Collection<?> || (value != null && value.getClass().isArray());
		if (type.isCollection()) {
			String refusal = RoutineValidation.nestingRefusal(type.collection(),
					RoutineMetadata.elementTypeOf(parameter.getParameterizedType()));
			if (refusal != null) {
				throw new PlsqlMappingException("parameter " + name + ": " + refusal);
			}
		} else if (many || Collection.class.isAssignableFrom(parameter.getType())) {
			throw new PlsqlMappingException("parameter " + name + " is declared as "
					+ type.composite().bindName() + ", a composite type, but is a "
					+ (value == null ? parameter.getType() : value.getClass()).getSimpleName()
					+ "; name the collection type in @PlsqlType");
		}
		int sqlType = type.isCollection() ? Types.ARRAY : Types.STRUCT;
		if (value == null) {
			log.sent(name, null);
			statement.setNull(position, sqlType, type.qualifiedBindName());
			return;
		}
		Object bindable = type.isCollection()
				? encoder.encodeCollection(value, type.collection(), literals, connection)
				: types.binding(value.getClass(), type.composite(), connection).toStruct(value, connection);
		log.sent(name, bindable);
		statement.setObject(position, bindable);
	}

	/**
	 * Without a dictionary, the SQL type of a scalar comes from what Java declares, and this is the
	 * only place in the library where that happens.
	 */
	private int resultSqlType(Method method) {
		AppliedConverter applied = scalarInBlock(RoutineMetadata.converterOfReturn(method, types.converters()), method);
		Class<?> returnType = applied == null ? ReturnType.of(method) : applied.databaseType();
		if (returnType == boolean.class || returnType == Boolean.class) {
			return method.isAnnotationPresent(PlsqlBoolean.class) ? Types.VARCHAR : Jdbc.ORACLE_PLSQL_BOOLEAN;
		}
		if (returnType == java.time.LocalDate.class) {
			return Types.DATE;
		}
		if (returnType == java.time.LocalDateTime.class) {
			return Types.TIMESTAMP;
		}
		if (returnType == java.time.OffsetDateTime.class || returnType == java.time.ZonedDateTime.class
				|| returnType == java.time.Instant.class) {
			return Jdbc.ORACLE_TIMESTAMPTZ;
		}
		if (Number.class.isAssignableFrom(returnType) || returnType.isPrimitive()) {
			return Types.NUMERIC;
		}
		return Types.VARCHAR;
	}

	/** A block has no scalar collection to convert per element, nor a dictionary to validate against. */
	private static AppliedConverter scalarInBlock(AppliedConverter applied, Method method) {
		if (applied != null && applied.elementWise()) {
			throw new PlsqlMappingException("in a block, a converter only applies to a scalar"
					+ " value");
		}
		return applied;
	}

	/** A cursor or a collection is read as it is for a routine, and a {@code NULL} collection becomes an empty list. */
	private Object decodeResult(CallableStatement statement, Method method, BlockMetadata metadata,
			SessionTypes sessionTypes, Connection connection) throws SQLException {

		JavaTarget target = JavaTarget.ofReturn(method, types.converters());
		if (metadata.cursorResult()) {
			return decoder.decodeCursor((java.sql.ResultSet) statement.getObject(metadata.resultPosition()), target,
					"the block", connection);
		}
		if (metadata.resultType().isBlank()) {
			AppliedConverter applied = scalarInBlock(RoutineMetadata.converterOfReturn(method, types.converters()),
					method);
			Object raw = ScalarCodec.read(statement, metadata.resultPosition(),
					applied == null ? ReturnType.of(method) : applied.databaseType());
			return applied == null
					? ScalarCodec.decode(raw, ReturnType.of(method), JavaTarget.returnConversion(method), connection)
					: applied.fromDatabase(ScalarCodec.decode(raw, applied.databaseType(),
							JavaTarget.returnConversion(method), connection));
		}
		Object raw = statement.getObject(metadata.resultPosition());
		BlockMetadata.DeclaredType declared = sessionTypes.of(metadata.resultType());
		if (declared.isCollection()) {
			scalarInBlock(target.converter(), method);
			if (raw == null) {
				return List.of();
			}
			java.lang.reflect.Type element = ValueDecoder.elementGenericOf(target);
			return decoder.decodeElements(OracleArrays.elements((Array) raw, ValueDecoder.classOf(element)),
					declared.collection(), element, null, target.conversion(), connection);
		}
		return raw == null ? null
				: types.binding(ReturnType.of(method), declared.composite(), connection).fromStruct((Struct) raw,
						connection);
	}
}
