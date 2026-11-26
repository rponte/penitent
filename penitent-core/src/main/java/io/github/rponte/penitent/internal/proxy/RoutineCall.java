package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.Method;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.Caches;
import io.github.rponte.penitent.internal.binding.TypeNameIntegration;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.JsonCodec;
import io.github.rponte.penitent.internal.convert.ScalarCodec;
import io.github.rponte.penitent.internal.driver.Carrier;
import io.github.rponte.penitent.internal.driver.Carriers;
import io.github.rponte.penitent.internal.driver.DriverFailure;
import io.github.rponte.penitent.internal.driver.DriverProfile;
import io.github.rponte.penitent.internal.log.CallLog;
import io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure;
import io.github.rponte.penitent.internal.metadata.CollectionType;
import io.github.rponte.penitent.internal.metadata.Dictionary;
import io.github.rponte.penitent.internal.metadata.OutParameters;
import io.github.rponte.penitent.internal.metadata.ReturnType;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.metadata.RoutineSignatures;
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

	/** Where the package, or the standalone routines, were found: resolved once, not per method (#169). */
	private final RoutineMetadata.Names names = new RoutineMetadata.Names();

	/**
	 * The signatures of the package's routines that the interface maps, read in one query on the first call of any
	 * method, or by validate, and kept while the proxy lives (#169). Each method is still described from them when
	 * it is first used. No lock is held while reading them: two threads may both read, and the first stored wins, as
	 * in {@link Caches}. Outside a package, as {@code @PlsqlSchema}, each routine is read on its own.
	 */
	private final AtomicReference<RoutineSignatures> signatures = new AtomicReference<>();

	/** The names of the routines the interface's methods call, for {@link #signatures}. */
	private final List<String> routineNames;

	/**
	 * The routines read again after a call failed as if they had changed, and found the same: the error was the
	 * routine's own, such as a {@code PLS-00306} of its dynamic PL/SQL (#165). For {@link #RECHECK_AFTER_NANOS}, a
	 * failure of that kind does not read the dictionary again, so that a routine that keeps failing so does not cost a
	 * describe on every call; after it, the next one does, and a real change made meanwhile is still noticed.
	 */
	private final ConcurrentMap<Method, Unchanged> unchanged = new ConcurrentHashMap<>();

	/**
	 * How long a routine read again and found the same is not read again for a failure of that kind: one second, fixed
	 * on purpose, not a setting (#165).
	 *
	 * <p><b>What it bounds.</b> A routine that keeps failing with {@code PLS-00306}, {@code PLS-00222} or
	 * {@code PLS-00302} of its own, as from its dynamic PL/SQL, read the dictionary again on every call: a describe's
	 * queries per failing call, under load, while something is already wrong. With this window, at most one read a
	 * second per method, whatever the rate of calls.
	 *
	 * <p><b>What it costs.</b> A real change of the routine made within the second after such a read is noticed at
	 * the first failure after the second has passed, not at once: until then, the failures go as Oracle says them.
	 * Only failures of those three errors wait; a call that works, or another error, is not affected.
	 *
	 * <p><b>Why fixed.</b> Neither end needs tuning: shorter brings back a read per failing call under load, and longer
	 * only delays noticing a change. HikariCP makes the same trade with a fixed window, 500 ms, in which a connection
	 * used a moment ago is not validated again. A setting would ask every user to choose a number that only matters
	 * while a routine keeps failing.
	 */
	private static final long RECHECK_AFTER_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(1);

	/** {@code routine}, as read again at {@code at}, from {@link System#nanoTime()}, and found the same. */
	private record Unchanged(RoutineMetadata routine, long at) {
	}

	RoutineCall(Class<?> type, PlsqlBind defaultBind, TypeCache types, ValueEncoder encoder, ValueDecoder decoder) {
		this.type = type;
		this.defaultBind = defaultBind;
		this.types = types;
		this.encoder = encoder;
		this.decoder = decoder;
		this.routineNames = java.util.Arrays.stream(type.getMethods())
				.filter(m -> !m.isDefault() && !java.lang.reflect.Modifier.isStatic(m.getModifiers())
						&& !m.isAnnotationPresent(PlsqlBlock.class))
				.map(RoutineCall::routineName).distinct().toList();
	}

	/** The routine a method calls: its {@code @PlsqlRoutine}, or its name, as {@code calculateFreight}. */
	private static String routineName(Method method) {
		PlsqlRoutine named = method.getAnnotation(PlsqlRoutine.class);
		return named != null ? RoutineMetadata.normalize(named.value()) : RoutineMetadata.toRoutineName(method.getName());
	}

	Object call(Method method, Object[] arguments, Connection connection) {
		// described before this call, or from signatures read before it: a failure may then be a change since (#165)
		boolean described = routines.containsKey(method) || signatures.get() != null;
		RoutineMetadata routine = Caches.get(routines, method, m -> describe(m, connection, false));
		requireToAgree(method, routine, arguments.length);
		try {
			return call(routine, method, arguments, ReturnType.of(method) != void.class, connection);
		} catch (SqlFailure e) {
			SQLException cause = e.failure().cause();
			if (cause.getErrorCode() == UNRESOLVED_TYPE || cause.getErrorCode() == INVALID_NAME_PATTERN) {
				new RoutineValidation(routine).explainUndescribableJson(method, new Dictionary(connection), cause);
			}
			throw StaleTypes.explained(described ? explainChange(method, routine, cause, connection, e) : e);
		}
	}

	/** {@code ORA-17060}: the driver could not describe a type, such as a RECORD with a JSON field. */
	private static final int UNRESOLVED_TYPE = 17060;

	/** {@code ORA-17074}: the driver could not resolve a type's name, such as a collection of JSON. */
	private static final int INVALID_NAME_PATTERN = 17074;

	/** Oracle's errors that a call with a signature the routine no longer has raises, from its {@code PLS-} code. */
	private static final List<String> SIGNATURE_CHANGED = List.of("PLS-00306", "PLS-00222", "PLS-00302");

	/**
	 * A call described before failed as if the routine, or a type it takes, had changed in the database since, as a
	 * deploy under a running application does. The routine, or the types, are described again, so that the cache holds
	 * them as they are now; if the method no longer fits, the error says so as a new proxy would, naming the change.
	 * The call is not repeated: it failed, and only the caller knows whether it may run again. If the method still
	 * fits a signature that changed, as a parameter renamed binding by position does, the failure says so, with
	 * Oracle's error after it and as its cause (#165). If the routine read again is the same, the error is the
	 * routine's own and goes on as it is, and for a short while such failures do not read the dictionary again (see
	 * {@link #unchanged}). So it does if the dictionary cannot be read, as when the error closed the connection, which
	 * 21c does: the cache is already cleared, and the next call reads it again.
	 */
	private SqlFailure explainChange(Method method, RoutineMetadata routine, SQLException cause, Connection connection,
			SqlFailure failure) {
		String message = String.valueOf(cause.getMessage());
		if (SIGNATURE_CHANGED.stream().anyMatch(message::contains)) {
			Unchanged last = unchanged.get(method);
			if (last != null && last.routine() == routine && System.nanoTime() - last.at() < RECHECK_AFTER_NANOS) {
				// read again a moment ago, and the same: the routine's own error, as Oracle says it
				return failure;
			}
			routines.remove(method, routine);
			// a deploy may also have moved the package, or pointed a synonym elsewhere
			names.clear();
			RoutineMetadata fresh;
			try {
				fresh = Caches.get(routines, method, m -> describe(m, connection, true));
				requireToAgree(method, fresh, method.getParameterCount());
			} catch (PlsqlMappingException e) {
				throw new PlsqlMappingException(routine.qualifiedName() + " changed since this proxy described it: "
						+ e.getMessage(), cause);
			} catch (SqlFailure unreadable) {
				routines.remove(method);
				return failure;
			}
			if (fresh.equals(routine)) {
				unchanged.put(method, new Unchanged(fresh, System.nanoTime()));
				return failure;
			}
			unchanged.remove(method);
			// the method fits the new signature: the next call uses it; this one, which did not run, says so first,
			// with Oracle's error after it, as its cause and with its code, so that it does not read as a broken package
			return new SqlFailure(failure.failure().sql(), new SQLException(routine.qualifiedName()
					+ " changed since this proxy described it, and was described again: the call did not run, and the"
					+ " next one uses the new signature. Parameters before: " + routine.parameters() + "; now: "
					+ fresh.parameters() + ". " + cause.getMessage(), cause.getSQLState(), cause.getErrorCode(), cause),
					failure.failure().phase());
		} else if (StaleTypes.OLDER_SHAPE.contains(cause.getErrorCode())) {
			types.clear();
			try {
				validate(method, connection);
			} catch (PlsqlMappingException e) {
				throw new PlsqlMappingException("a type of " + routine.qualifiedName()
						+ " changed since this proxy described it: " + e.getMessage(), cause);
			} catch (SqlFailure unreadable) {
				types.clear();
			}
		}
		return failure;
	}

	/**
	 * {@code value}, as the return value of {@code method}, encoded as a call would decode it backwards: through the
	 * method's converter and its {@code @PlsqlBoolean}, an {@code Optional} unwrapped. For a tool that writes the value
	 * elsewhere, such as the mocks of {@code penitent-test}, which write it as PL/SQL; a call never sends it.
	 */
	Object encodeReturn(Method method, Object value, Connection connection) throws SQLException {
		RoutineMetadata routine = describeOnce(method, connection);
		Argument target = routine.returnValue().orElseThrow(() -> new PlsqlMappingException(
				routine.qualifiedName() + " returns no value"));
		Object unwrapped = value instanceof java.util.Optional<?> optional ? optional.orElse(null) : value;
		Object converted = converted(RoutineMetadata.converterOfReturn(method, types.converters()), unwrapped,
				"the return value", routine);
		if (target.isJson()) {
			// a JSON value as its text, checked and written as a parameter's is (see toDatabase): a stub refuses what a
			// call refuses, and a Map, a List or a scalar becomes its text
			try {
				converted = JsonCodec.text(converted);
			} catch (ScalarCodec.ConversionException e) {
				throw new PlsqlMappingException("the return value of " + routine.qualifiedName() + ": " + e.getMessage(),
						e);
			}
		}
		return encoder.encodeForWriting(converted, target, method.getAnnotation(PlsqlBoolean.class), routine.schema(),
				connection);
	}

	/**
	 * {@code value}, given for the {@code index}-th Java parameter of {@code method}, encoded as a call would send it to
	 * the Oracle parameter that Java parameter binds, by position or by name: through its converter and its
	 * {@code @PlsqlBoolean}. For a tool that writes the value elsewhere, as {@link #encodeReturn}.
	 */
	Object encodeParameter(Method method, int index, Object value, Connection connection) throws SQLException {
		RoutineMetadata routine = describeOnce(method, connection);
		Argument target = routine.parameters().get(index);
		java.lang.reflect.Parameter javaParameter = method.getParameters()[index];
		return encoder.encodeForWriting(toDatabase(value, method, javaParameter, target, routine), target,
				javaParameter.getAnnotation(PlsqlBoolean.class), routine.schema(), connection);
	}

	/** What a call of {@code method} would run: the routine, its owner and the version that fits the method. */
	RoutineMetadata describeOnce(Method method, Connection connection) {
		RoutineMetadata routine = Caches.get(routines, method, m -> describe(m, connection, false));
		requireToAgree(method, routine, method.getParameterCount());
		return routine;
	}

	/**
	 * Everything a call would check, without calling: the signature, the method against it, and the
	 * composite types the method declares, all the way down. What stays for the call is what depends
	 * on a value: a cursor's columns, and a composite sent as a subclass of the declared type.
	 */
	void validate(Method method, Connection connection) {
		RoutineMetadata routine = Caches.get(routines, method, m -> describe(m, connection, false));
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
			CollectionType collection = types.collectionType(argument, schema, connection);
			if ("JSON".equals(collection.scalarElement())) {
				throw new PlsqlMappingException((argument.isReturnValue() ? "the return value" : argument.name()) + " is "
						+ RoutineValidation.JSON_COLLECTION);
			}
			resolveElements(collection, RoutineMetadata.elementTypeOf(generic), connection);
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

	/**
	 * {@code method}'s routine, from the interface's signatures, read once, or, if {@code fresh}, as after a call
	 * failed as if it had changed, from the dictionary as it is now, for this routine alone.
	 */
	private RoutineMetadata describe(Method method, Connection connection, boolean fresh) {
		// from the proxy's interface, not the one declaring the method: that may be an inherited one, without the annotation
		PlsqlPackage pkg = type.getAnnotation(PlsqlPackage.class);
		PlsqlSchema container = type.getAnnotation(PlsqlSchema.class);

		String declaredSchema = pkg != null ? pkg.schema() : container.value();
		String schema = declaredSchema.isBlank() ? null : RoutineMetadata.normalize(declaredSchema);
		// without a package, the routine lives directly in the schema
		String packageName = pkg != null ? RoutineMetadata.normalize(pkg.value()) : null;
		PlsqlBind declared = pkg != null ? pkg.bind() : container.bind();
		PlsqlBind bind = declared == PlsqlBind.DEFAULT ? defaultBind : declared;

		String routineName = routineName(method);

		long start = System.nanoTime();
		Dictionary dictionary = new Dictionary(connection);
		RoutineMetadata routine;
		RoutineMetadata.Target target = packageName == null || fresh ? null
				: RoutineMetadata.target(dictionary, names, schema, packageName, routineName);
		RoutineSignatures listed = target == null ? null : signaturesOf(target, dictionary);
		if (listed != null && listed.of(target.routine()).isPresent()) {
			routine = RoutineMetadata.of(dictionary, target, listed, method, types.converters(),
					bind == PlsqlBind.BY_NAME);
		} else {
			// a routine the signatures do not list is read on its own, as it is now: it may not exist, it may have been
			// created since they were read, or it may be a procedure without arguments, which has no rows to list
			routine = RoutineMetadata.read(dictionary, names, schema, packageName, routineName, method,
					types.converters(), bind == PlsqlBind.BY_NAME);
		}
		// a wrong arity has its own message, at call time
		if (method.getParameterCount() <= routine.parameters().size()) {
			RoutineValidation validation = new RoutineValidation(routine);
			validation.requireCollectionsToBeSupported(method, dictionary, types.converters());
			validation.requireConvertersToFit(method, dictionary, types.converters());
			validation.requireTemporalElementsToAgree(method, dictionary, types.converters());
			validation.requireNestingToAgree(method, dictionary);
			validation.requireJsonToBeSupported(method, DriverProfile.of(connection));
			validation.requireJsonTypesToFit(method, types.converters());
		}
		CallLog.described(routine.qualifiedName(), start);
		warnIfNullBooleanMisread(routine, method, connection);
		return routine;
	}

	/** The interface's signatures, read now if they were not, or if the package now resolves elsewhere. */
	private RoutineSignatures signaturesOf(RoutineMetadata.Target target, Dictionary dictionary) {
		RoutineSignatures known = signatures.get();
		if (known != null && known.isOf(target.owner(), target.packageName())) {
			return known;
		}
		RoutineSignatures read = RoutineSignatures.read(dictionary, target.owner(), target.packageName(), routineNames);
		// none found, as before the package is deployed: nothing worth keeping
		if (read.isEmpty()) {
			return read;
		}
		if (signatures.compareAndSet(known, read)) {
			return read;
		}
		RoutineSignatures raced = signatures.get();
		return raced != null && raced.isOf(target.owner(), target.packageName()) ? raced : read;
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
		// a type that the driver does not know, such as JSON with driver 19, is converted in the database
		Carriers carriers = Carriers.of(DriverProfile.of(connection));
		String sql = routine.callStatement(args.length, expectsResult, carriers);
		CallLog log = CallLog.routine(routine.qualifiedName(), method, sql,
				// every call names its parameters, by position too (see RoutineMetadata.callStatement)
				routine.parameters().stream().limit(args.length).map(Argument::name).toList());
		try {
			Object result = call(routine, method, args, expectsResult, connection, sql, log, carriers);
			log.completed();
			return result;
		} catch (RuntimeException e) {
			log.failed(e);
			throw e;
		}
	}

	private Object call(RoutineMetadata routine, Method method, Object[] args, boolean expectsResult,
			Connection connection, String sql, CallLog log, Carriers carriers) {
		List<Argument> parameters = routine.parameters();

		try (CallableStatement statement = connection.prepareCall(sql)) {

			int offset = expectsResult ? 1 : 0;
			if (expectsResult) {
				try {
					registerOut(statement, 1, routine.returnValue().orElseThrow(), routine.schema(), connection,
							carriers);
				} catch (DriverFailure e) {
					throw bindingFailure(e, "the return value of " + routine.qualifiedName(), sql);
				}
			}
			java.lang.reflect.Parameter[] javaParameters = method.getParameters();
			for (int i = 0; i < args.length; i++) {
				Argument parameter = parameters.get(i);
				int position = i + 1 + offset;
				PlsqlBoolean literals = javaParameters[i].getAnnotation(PlsqlBoolean.class);
				try {
					if (parameter.isOut()) {
						if (args[i] == null) {
							throw new PlsqlMappingException(parameter.name() + " of " + routine.qualifiedName()
									+ " is " + parameter.inOut() + "; pass a PlsqlOut, not null");
						}
						if (parameter.isIn()) {
							bindValue(statement, position, toDatabase(((PlsqlOut<?>) args[i]).get(), method,
									javaParameters[i], parameter, routine), parameter, literals, routine.schema(),
									connection, log, carriers);
						}
						registerOut(statement, position, parameter, routine.schema(), connection, carriers);
					} else {
						bindValue(statement, position, toDatabase(args[i], method, javaParameters[i], parameter,
								routine), parameter, literals, routine.schema(), connection, log, carriers);
					}
				} catch (DriverFailure e) {
					throw bindingFailure(e, parameter.name() + " of " + routine.qualifiedName(), sql);
				}
			}

			log.executing();
			Jdbc.execute(statement, sql, routine.qualifiedName());
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
			} catch (DriverFailure e) {
				throw e.ofCall(routine.qualifiedName(), sql, PlsqlFailure.Phase.RESULT);
			}
		} catch (SQLException e) {
			throw new SqlFailure(sql, e, PlsqlFailure.Phase.PREPARATION);
		} catch (DriverFailure e) {
			// preparing or closing the statement
			throw e.ofCall(routine.qualifiedName(), sql, e.operation().endsWith(".close") ? PlsqlFailure.Phase.RESULT
					: PlsqlFailure.Phase.PREPARATION);
		}
	}

	/**
	 * A failure of the driver while binding {@code what}: a value it refused, with the connection kept, or, for a
	 * round trip such as writing a temporary LOB, its own failure, with the connection discarded (see
	 * {@link DriverFailure}).
	 */
	static RuntimeException bindingFailure(DriverFailure e, String what, String sql) {
		return e.inBinding() ? e.ofBinding(what) : e.ofCall(what, sql, PlsqlFailure.Phase.PREPARATION);
	}

	/**
	 * The value that actually goes to the database. A whole collection that is null does not go through the converter.
	 * A JSON value goes as its text, which {@link JsonCodec#text} writes from a {@code Map}, a {@code List} or a scalar.
	 */
	private Object toDatabase(Object value, Method method, java.lang.reflect.Parameter javaParameter,
			Argument parameter, RoutineMetadata routine) {
		Object converted = converted(RoutineMetadata.converterOf(method, javaParameter, types.converters()), value,
				parameter.name(), routine);
		if (!parameter.isJson()) {
			return converted;
		}
		try {
			return JsonCodec.text(converted);
		} catch (ScalarCodec.ConversionException e) {
			throw new PlsqlMappingException(parameter.name() + " of " + routine.qualifiedName() + ": " + e.getMessage(),
					e);
		}
	}

	/** {@code value} through {@code applied}, element by element where it applies to the elements of a list. */
	private static Object converted(AppliedConverter applied, Object value, String what, RoutineMetadata routine) {
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
			throw new PlsqlMappingException(what + " of " + routine.qualifiedName() + ": " + e.getMessage(), e);
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

	private static void registerOut(CallableStatement statement, int position, Argument argument, String schema,
			Connection connection, Carriers carriers) throws SQLException {
		Optional<Carrier> carrier = carriers.of(argument);
		if (carrier.isPresent()) {
			carrier.get().registerOut(statement, position);
		} else if (argument.isStruct() || argument.isCollection()) {
			statement.registerOutParameter(position, Jdbc.sqlTypeOf(argument),
					bindTypeNameOf(argument, schema, connection));
		} else {
			// the form with a type name is for named types: with an empty name, a RAW yields ORA-17012
			statement.registerOutParameter(position, Jdbc.sqlTypeOf(argument));
		}
	}

	private void bindValue(CallableStatement statement, int position, Object value, Argument parameter,
			PlsqlBoolean literals, String schema, Connection connection, CallLog log, Carriers carriers)
			throws SQLException {
		Object bindable = encoder.encode(value, parameter, literals, schema, connection);
		log.sent(parameter.name(), bindable);
		Optional<Carrier> carrier = carriers.of(parameter);
		if (bindable == null) {
			bindNull(statement, position, parameter, schema, connection, carriers);
		} else if (carrier.isPresent()) {
			carrier.get().bind(statement, position, bindable);
		} else if (parameter.isBoolean() && bindable instanceof Boolean) {
			statement.setObject(position, bindable, Jdbc.ORACLE_PLSQL_BOOLEAN);
		} else if (parameter.isJson()) {
			// the JSON text, with the JSON type; as plain text, PLS-00306
			bindJson(statement, position, bindable, parameter);
		} else if (ValueEncoder.binaryFloatingPoint(parameter) && bindable instanceof Number number) {
			// bound with its type: as the default NUMBER, a NaN or an infinity fails in the driver
			Jdbc.setBinaryFloatingPoint(statement, position, number, parameter.dataType());
		} else {
			statement.setObject(position, ScalarCodec.forDriver(bindable));
		}
	}

	/**
	 * The JSON text with the JSON type, which drivers 21 and later parse into OSON, Oracle's binary form, before
	 * sending it. The text was already checked ({@code JsonCodec.requireValid}), so what the driver still refuses here
	 * is a limit of the binary form, which each driver sets by version (#140): a key longer than 256 bytes with driver
	 * 21 ("the UTF-8 encoded key length must not be greater than 256 bytes"), or 65,535 with driver 23
	 * ({@code ORA-26338}); and nesting that driver 21 does not handle, as a bare {@code ArrayIndexOutOfBoundsException}
	 * at 10,000 levels, which driver 23 takes. Each becomes a {@code PlsqlMappingException} with the driver's own
	 * message, rather than a runtime exception escaping, or an error that looks like the database's: the library
	 * writes no such limit of its own, so a driver that raises it just works.
	 *
	 * <p>Only the driver's JSON errors are taken: its codes for JSON, {@code ORA-26300} to {@code ORA-26399} with driver
	 * 23 and the internal error {@code ORA-17001} with driver 21, and a runtime exception. Any other failure while
	 * binding goes as it is.
	 */
	static void bindJson(CallableStatement statement, int position, Object text, Argument parameter)
			throws SQLException {
		try {
			statement.setObject(position, text, Jdbc.ORACLE_JSON);
		} catch (DriverFailure e) {
			// behind the boundary, the driver's runtime exception comes marked
			throw refusedJson(parameter, (Exception) e.getCause());
		} catch (RuntimeException e) {
			throw refusedJson(parameter, e);
		} catch (SQLException e) {
			int code = e.getErrorCode();
			if (code == 17001 || (code >= 26300 && code <= 26399)) {
				throw refusedJson(parameter, e);
			}
			throw e;
		}
	}

	private static PlsqlMappingException refusedJson(Argument parameter, Exception cause) {
		return new PlsqlMappingException(parameter.name() + " is Oracle's JSON type, and the driver refused the text"
				+ " while binding it, at a limit of its own: " + cause.getClass().getSimpleName() + ": "
				+ cause.getMessage(), cause);
	}

	/**
	 * A null needs its type: that is what Oracle uses to resolve the version of an overloaded
	 * routine, and without the name a composite or a collection yields {@code PLS-00306}.
	 */
	private static void bindNull(CallableStatement statement, int position, Argument target, String schema,
			Connection connection, Carriers carriers) throws SQLException {
		Optional<Carrier> carrier = carriers.of(target);
		if (carrier.isPresent()) {
			carrier.get().bindNull(statement, position);
		} else if (target.isStruct() || target.isCollection()) {
			statement.setNull(position, Jdbc.sqlTypeOf(target), bindTypeNameOf(target, schema, connection));
		} else {
			statement.setNull(position, Jdbc.sqlTypeOf(target));
		}
	}

	/**
	 * The type name to bind: from the dictionary for a composite or a collection, as the connection names it (see
	 * {@code TypeNameIntegration}), and empty for everything else.
	 */
	private static String bindTypeNameOf(Argument argument, String schema, Connection connection) throws SQLException {
		if (argument.isStruct()) {
			return TypeNameIntegration.compositeNameOf(connection, argument.compositeType(schema));
		}
		return argument.isCollection() ? TypeNameIntegration.collectionNameOf(connection, argument.collectionBindName())
				: argument.typeName();
	}
}
