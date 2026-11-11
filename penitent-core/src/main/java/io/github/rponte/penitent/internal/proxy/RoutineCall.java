package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.Caches;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.ScalarCodec;
import io.github.rponte.penitent.internal.log.CallLog;
import io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure;
import io.github.rponte.penitent.internal.metadata.CollectionType;
import io.github.rponte.penitent.internal.metadata.Dictionary;
import io.github.rponte.penitent.internal.metadata.OutParameters;
import io.github.rponte.penitent.internal.metadata.ReturnType;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.metadata.RoutineValidation;

/**
 * The call of a procedure or function: its signature comes from the dictionary, is checked against
 * the method once, and then each call binds by position, or by name with {@link PlsqlBind#BY_NAME}.
 */
final class RoutineCall {

	private final Class<?> type;
	private final PlsqlBind defaultBind;
	private final TypeCache types;
	private final ValueEncoder encoder;
	private final ValueDecoder decoder;

	/** Per instance, for the same reason as {@link TypeCache}. */
	private final ConcurrentMap<Method, RoutineMetadata> routines = new ConcurrentHashMap<>();

	RoutineCall(Class<?> type, PlsqlBind defaultBind, TypeCache types, ValueEncoder encoder, ValueDecoder decoder) {
		this.type = type;
		this.defaultBind = defaultBind;
		this.types = types;
		this.encoder = encoder;
		this.decoder = decoder;
	}

	Object call(Method method, Object[] arguments, Connection connection) {
		RoutineMetadata routine = Caches.get(routines, method, m -> describe(m, connection));
		requireToAgree(method, routine, arguments.length);
		return call(routine, method, arguments, ReturnType.of(method) != void.class, connection);
	}

	/**
	 * Everything a call would check, without calling: the signature, the method against it, and the
	 * composite types the method declares, all the way down. What stays for the call is what depends
	 * on a value: a cursor's columns, and a composite sent as a subclass of the declared type.
	 */
	void validate(Method method, Connection connection) {
		RoutineMetadata routine = Caches.get(routines, method, m -> describe(m, connection));
		requireToAgree(method, routine, method.getParameterCount());

		java.lang.reflect.Parameter[] javaParameters = method.getParameters();
		List<Argument> parameters = routine.parameters();
		for (int i = 0; i < javaParameters.length; i++) {
			Argument parameter = parameters.get(i);
			if (parameter.isOut()) {
				requireReadable(JavaTarget.ofOut(method, javaParameters[i], parameter, types.converters()), parameter,
						routine, connection);
			} else if (RoutineMetadata.converterOf(method, javaParameters[i], types.converters()) == null) {
				resolveComposites(javaParameters[i].getType(), javaParameters[i].getParameterizedType(), parameter,
						routine.schema(), connection);
			}
		}
		if (routine.returnValue().isPresent()) {
			requireReadable(JavaTarget.ofReturn(method, types.converters()), routine.returnValue().get(), routine,
					connection);
		}
	}

	/** What reading the value back would check first, and the composites it would land in. */
	private void requireReadable(JavaTarget target, Argument argument, RoutineMetadata routine, Connection connection) {
		if (argument.isCursor()) {
			ValueDecoder.requireList(target, routine.qualifiedName(), "a cursor");
		} else if (argument.isCollection()) {
			ValueDecoder.requireList(target, routine.qualifiedName(), "a collection");
		}
		if (target.converter() == null) {
			resolveComposites(target.type(), target.generic(), argument, routine.schema(), connection);
		}
	}

	private void requireToAgree(Method method, RoutineMetadata routine, int argumentCount) {
		RoutineValidation validation = new RoutineValidation(routine);
		validation.requireArity(argumentCount);
		validation.requireOutParametersToAgree(method);
		validation.requireTemporalTypesToAgree(method, types.converters());

		if (ReturnType.of(method) != void.class) {
			validation.requireFunction();
		} else {
			validation.requireProcedure();
		}
	}

	/** A composite, or a collection of composites at any depth, with the Java type the method declares for it. */
	private void resolveComposites(Class<?> javaType, java.lang.reflect.Type generic, Argument argument,
			String schema, Connection connection) {
		if (argument.isStruct()) {
			types.binding(javaType, argument.compositeType(schema), connection).resolveNested(connection);
		} else if (argument.isCollection()) {
			resolveElements(types.collectionType(argument, schema, connection), RoutineMetadata.elementTypeOf(generic),
					connection);
		}
	}

	private void resolveElements(CollectionType collection, java.lang.reflect.Type element, Connection connection) {
		if (element == null) {
			return;
		}
		if (collection.compositeElement() != null && element instanceof Class<?> elementClass) {
			types.binding(elementClass, collection.compositeElement(), connection).resolveNested(connection);
		} else if (collection.collectionElement() != null) {
			resolveElements(collection.collectionElement(), RoutineMetadata.elementTypeOf(element), connection);
		}
	}

	private RoutineMetadata describe(Method method, Connection connection) {
		// from the proxy's interface, not the one declaring the method: that may be an inherited one, without the annotation
		PlsqlPackage pkg = type.getAnnotation(PlsqlPackage.class);
		PlsqlSchema container = type.getAnnotation(PlsqlSchema.class);

		String declaredSchema = pkg != null ? pkg.schema() : container.value();
		String schema = declaredSchema.isBlank() ? null : RoutineMetadata.normalize(declaredSchema);
		// without a package, the routine lives directly in the schema
		String packageName = pkg != null ? RoutineMetadata.normalize(pkg.value()) : null;
		PlsqlBind declared = pkg != null ? pkg.bind() : container.bind();
		PlsqlBind bind = declared == PlsqlBind.DEFAULT ? defaultBind : declared;

		PlsqlRoutine named = method.getAnnotation(PlsqlRoutine.class);
		String routineName = named != null
				? RoutineMetadata.normalize(named.value())
				: RoutineMetadata.toRoutineName(method.getName());

		long start = System.nanoTime();
		Dictionary dictionary = new Dictionary(connection);
		RoutineMetadata routine = RoutineMetadata.read(dictionary, schema, packageName, routineName, method,
				types.converters(), bind == PlsqlBind.BY_NAME);
		// a wrong arity has its own message, at call time
		if (method.getParameterCount() <= routine.parameters().size()) {
			RoutineValidation validation = new RoutineValidation(routine);
			validation.requireCollectionsToBeSupported(method, dictionary, types.converters());
			validation.requireConvertersToFit(method, dictionary, types.converters());
			validation.requireTemporalElementsToAgree(method, dictionary, types.converters());
			validation.requireNestingToAgree(method, dictionary);
		}
		CallLog.described(routine.qualifiedName(), start);
		warnIfNullBooleanMisread(routine, method, connection);
		return routine;
	}

	/** A BOOLEAN that comes back, which some versions read wrong when null: see {@link NullBooleanOutputs}. */
	private static void warnIfNullBooleanMisread(RoutineMetadata routine, Method method, Connection connection) {
		if (routine.returnValue().filter(Argument::isBoolean).isPresent()) {
			NullBooleanOutputs.warnIfMisread(method, routine.qualifiedName() + " returns a BOOLEAN", connection);
		} else if (routine.parameters().stream().anyMatch(p -> p.isOut() && p.isBoolean())) {
			NullBooleanOutputs.warnIfMisread(method, routine.qualifiedName() + " has a BOOLEAN that comes back",
					connection);
		}
	}

	private Object call(RoutineMetadata routine, Method method, Object[] args, boolean expectsResult,
			Connection connection) {
		String sql = routine.callStatement(args.length, expectsResult);
		CallLog log = CallLog.routine(routine.qualifiedName(), method, sql,
				routine.byName() ? routine.parameters().stream().limit(args.length).map(Argument::name).toList() : null);
		try {
			Object result = call(routine, method, args, expectsResult, connection, sql, log);
			log.completed();
			return result;
		} catch (RuntimeException e) {
			log.failed(e);
			throw e;
		}
	}

	private Object call(RoutineMetadata routine, Method method, Object[] args, boolean expectsResult,
			Connection connection, String sql, CallLog log) {
		List<Argument> parameters = routine.parameters();

		try (CallableStatement statement = connection.prepareCall(sql)) {

			int offset = expectsResult ? 1 : 0;
			if (expectsResult) {
				registerOut(statement, 1, routine.returnValue().orElseThrow(), routine.schema());
			}
			java.lang.reflect.Parameter[] javaParameters = method.getParameters();
			for (int i = 0; i < args.length; i++) {
				Argument parameter = parameters.get(i);
				int position = i + 1 + offset;
				PlsqlBoolean literals = javaParameters[i].getAnnotation(PlsqlBoolean.class);
				if (parameter.isOut()) {
					if (args[i] == null) {
						throw new PlsqlMappingException(parameter.name() + " of " + routine.qualifiedName()
								+ " is " + parameter.inOut() + "; pass a PlsqlOut, not null");
					}
					if (parameter.isIn()) {
						bindValue(statement, position,
								toDatabase(((PlsqlOut<?>) args[i]).get(), method, javaParameters[i], parameter, routine),
								parameter, literals, routine.schema(), connection, log);
					}
					registerOut(statement, position, parameter, routine.schema());
				} else {
					bindValue(statement, position, toDatabase(args[i], method, javaParameters[i], parameter, routine),
							parameter, literals, routine.schema(), connection, log);
				}
			}

			log.executing();
			Jdbc.execute(statement, sql);
			try {
				readOutParameters(statement, routine, method, args, offset, connection, log);
				log.outsRead();
				if (!expectsResult) {
					return null;
				}
				try {
					return decoder.decodeValue(statement, 1, routine.returnValue().orElseThrow(),
							JavaTarget.ofReturn(method, types.converters()), routine, connection, log, null);
				} catch (ScalarCodec.ConversionException e) {
					throw new PlsqlMappingException("the return value of " + routine.qualifiedName() + ": "
							+ e.getMessage(), e);
				}
			} catch (SQLException e) {
				throw new SqlFailure(sql, e, PlsqlFailure.Phase.RESULT);
			}
		} catch (SQLException e) {
			throw new SqlFailure(sql, e, PlsqlFailure.Phase.PREPARATION);
		}
	}

	/** The value that actually goes to the database. A whole collection that is null does not go through the converter. */
	private Object toDatabase(Object value, Method method, java.lang.reflect.Parameter javaParameter,
			Argument parameter, RoutineMetadata routine) {
		AppliedConverter applied = RoutineMetadata.converterOf(method, javaParameter, types.converters());
		if (applied == null) {
			return value;
		}
		try {
			if (!applied.elementWise()) {
				return applied.toDatabase(value);
			}
			return value == null ? null
					: java.util.Arrays.stream(ValueEncoder.toArray(value)).map(applied::toDatabase).toList().toArray();
		} catch (ScalarCodec.ConversionException e) {
			throw new PlsqlMappingException(parameter.name() + " of " + routine.qualifiedName() + ": "
					+ e.getMessage(), e);
		}
	}

	private void readOutParameters(CallableStatement statement, RoutineMetadata routine, Method method,
			Object[] args, int offset, Connection connection, CallLog log) throws SQLException {
		List<Argument> parameters = routine.parameters();
		java.lang.reflect.Parameter[] javaParameters = method.getParameters();

		for (int i = 0; i < args.length; i++) {
			if (parameters.get(i).isOut()) {
				JavaTarget target = JavaTarget.ofOut(method, javaParameters[i], parameters.get(i), types.converters());
				try {
					PlsqlOutAccess.set((PlsqlOut<?>) args[i], decoder.decodeValue(statement, i + 1 + offset,
							parameters.get(i), target, routine, connection, log, parameters.get(i).name()));
				} catch (ScalarCodec.ConversionException e) {
					throw new PlsqlMappingException(parameters.get(i).name() + " of " + routine.qualifiedName() + ": "
							+ e.getMessage(), e);
				}
			}
		}
	}

	private static void registerOut(CallableStatement statement, int position, Argument argument, String schema)
			throws SQLException {
		if (argument.isStruct() || argument.isCollection()) {
			statement.registerOutParameter(position, Jdbc.sqlTypeOf(argument), bindTypeNameOf(argument, schema));
		} else {
			// the form with a type name is for named types: with an empty name, a RAW yields ORA-17012
			statement.registerOutParameter(position, Jdbc.sqlTypeOf(argument));
		}
	}

	private void bindValue(CallableStatement statement, int position, Object value, Argument parameter,
			PlsqlBoolean literals, String schema, Connection connection, CallLog log) throws SQLException {
		Object bindable = encoder.encode(value, parameter, literals, schema, connection);
		log.sent(parameter.name(), bindable);
		if (bindable == null) {
			bindNull(statement, position, parameter, schema);
		} else if (parameter.isBoolean() && bindable instanceof Boolean) {
			statement.setObject(position, bindable, Jdbc.ORACLE_PLSQL_BOOLEAN);
		} else {
			statement.setObject(position, ScalarCodec.forDriver(bindable));
		}
	}

	/**
	 * A null needs its type: that is what Oracle uses to resolve the version of an overloaded
	 * routine, and without the name a composite or a collection yields {@code PLS-00306}.
	 */
	private static void bindNull(CallableStatement statement, int position, Argument target, String schema)
			throws SQLException {
		if (target.isStruct() || target.isCollection()) {
			statement.setNull(position, Jdbc.sqlTypeOf(target), bindTypeNameOf(target, schema));
		} else {
			statement.setNull(position, Jdbc.sqlTypeOf(target));
		}
	}

	/** The type name to bind: from the dictionary for a composite, empty for everything else. */
	private static String bindTypeNameOf(Argument argument, String schema) {
		if (argument.isStruct()) {
			return argument.compositeType(schema).qualifiedBindName();
		}
		return argument.isCollection() ? argument.collectionBindName() : argument.typeName();
	}
}
