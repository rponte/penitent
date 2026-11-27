package io.github.rponte.penitent.internal.metadata;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlParam;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.AppliedConverter;
import io.github.rponte.penitent.internal.convert.ConverterLookup;
import io.github.rponte.penitent.internal.convert.JsonCodec;
import io.github.rponte.penitent.internal.driver.Carrier;
import io.github.rponte.penitent.internal.driver.Carriers;

/**
 * The signature of a routine, read from the Oracle data dictionary.
 *
 * <p>The dictionary is the source of truth: the Java interface declares the intent, and
 * {@link RoutineValidation} checks it against what this class read. That is why no type name or
 * {@code java.sql.Types} constant has to be written anywhere: {@code ALL_ARGUMENTS} already has
 * everything, including {@code type_name} for composite parameters.
 */
public record RoutineMetadata(String schema, String packageName, String routineName, List<Argument> arguments,
		boolean byName) {


	/**
	 * A row of {@code ALL_ARGUMENTS}. {@code typeName} is only filled in for a composite type, and
	 * {@code position == 0} with {@code name == null} is the function's <b>return value</b>, not a
	 * parameter, a detail that costs a wrong arity to anyone who does not know it.
	 */
	public record Argument(int position, String name, String dataType, String inOut, String typeOwner, String typeName,
			String typeSubname, boolean defaulted) {

		public boolean isReturnValue() {
			return position == 0;
		}

		/** OBJECT, PL/SQL RECORD and %ROWTYPE: everything that travels as a {@code Struct}. */
		public boolean isStruct() {
			return "OBJECT".equals(dataType) || "PL/SQL RECORD".equals(dataType);
		}

		/** {@code PL/SQL TABLE} is how the dictionary spells an {@code INDEX BY}. */
		public boolean isCollection() {
			return "TABLE".equals(dataType) || "VARRAY".equals(dataType) || "PL/SQL TABLE".equals(dataType);
		}

		/**
		 * The dictionary says the routine returns a {@code REF CURSOR}, and nothing more: the
		 * columns are in the routine's body, not in its signature. See
		 * {@link io.github.rponte.penitent.internal.binding.CursorBinding}.
		 */
		public boolean isCursor() {
			return "REF CURSOR".equals(dataType);
		}

		/**
		 * The name JDBC addresses the <b>collection</b> by, and the same rule as for a RECORD
		 * applies: when declared in a package spec, it is qualified with the package.
		 *
		 * <p>Collections declared in a package spec are common in real schemas, and for them the bare
		 * name does not resolve: it gives {@code ORA-17074: Invalid name pattern}. The element is a
		 * different identifier, resolved in {@link #elementTypeOf}.
		 */
		public String collectionBindName() {
			if (typeOwner != null) {
				// with every part in quotes when one of them needs quotes, as CompositeType.qualified explains
				return typeSubname != null ? CompositeType.qualified(typeOwner, typeName, typeSubname)
						: CompositeType.qualified(typeOwner, null, typeName);
			}
			return typeSubname != null ? typeName + "." + typeSubname : typeName;
		}

		/**
		 * How to address this argument's composite type, derived from the dictionary.
		 *
		 * <p>This is the rule that unlocks PL/SQL RECORD and %ROWTYPE, and it comes entirely from
		 * {@code type_name}/{@code type_subname}:
		 *
		 * <ul>
		 *   <li>OBJECT → the {@code type_name} itself;
		 *   <li>package spec RECORD → {@code PACKAGE.RECORD}, because since 12.1 a PL/SQL type
		 *       declared in a spec is addressable that way;
		 *   <li>table {@code %ROWTYPE} → {@code TABLE%ROWTYPE}, with the literal suffix. The
		 *       {@code type_subname} is null here, which can make it look as if there were no name
		 *       to qualify.
		 * </ul>
		 */
		public CompositeType compositeType(String routineSchema) {
			String owner = ownerOr(routineSchema);
			if ("OBJECT".equals(dataType)) {
				return new CompositeType(CompositeType.Kind.OBJECT, owner, null, typeName, typeName);
			}
			if ("PL/SQL RECORD".equals(dataType)) {
				return typeSubname != null
						? new CompositeType(CompositeType.Kind.PLSQL_RECORD, owner, typeName, typeSubname,
								typeName + "." + typeSubname)
						: new CompositeType(CompositeType.Kind.ROWTYPE, owner, null, typeName,
								typeName + "%ROWTYPE");
			}
			throw new PlsqlMappingException(name + " is " + dataType + ", not a composite type");
		}

		public boolean isBoolean() {
			return "BOOLEAN".equals(dataType) || "PL/SQL BOOLEAN".equals(dataType);
		}

		/** Oracle's JSON type, from 21c on, which a value crosses as its JSON text. */
		public boolean isJson() {
			return "JSON".equals(dataType);
		}

		public boolean isIn() {
			return "IN".equals(inOut) || "IN/OUT".equals(inOut);
		}

		public boolean isOut() {
			return "OUT".equals(inOut) || "IN/OUT".equals(inOut);
		}

		/** The type may live in a schema other than the routine's. */
		public String ownerOr(String routineSchema) {
			return typeOwner != null ? typeOwner : routineSchema;
		}

		/**
		 * Whether a value declared with this Java type can go into this argument. It is only used to
		 * choose between versions of an overloaded routine; the database is still the one that really
		 * checks. An unknown Oracle type accepts, so that a version is not discarded for lack of
		 * knowledge on this side.
		 */
		boolean accepts(Class<?> javaType) {
			if (javaType == Object.class) {
				return true;
			}
			if (isStruct()) {
				return acceptsComposite(javaType);
			}
			if (isCollection()) {
				return Collection.class.isAssignableFrom(javaType)
						|| (javaType.isArray() && javaType != byte[].class);
			}
			if (isCursor()) {
				return List.class.isAssignableFrom(javaType);
			}
			if (dataType.contains("BOOLEAN")) {
				return javaType == boolean.class || javaType == Boolean.class;
			}
			if (isJson()) {
				return JsonCodec.accepts(javaType);
			}
			if (dataType.contains("CHAR") || dataType.endsWith("CLOB")) {
				return CharSequence.class.isAssignableFrom(javaType) || javaType.isEnum()
						|| javaType == boolean.class || javaType == Boolean.class;
			}
			if (dataType.startsWith("TIMESTAMP") && dataType.contains(" WITH ")) {
				return javaType == java.time.OffsetDateTime.class || javaType == java.time.ZonedDateTime.class
						|| javaType == java.time.Instant.class;
			}
			if (dataType.equals("DATE") || dataType.startsWith("TIMESTAMP")) {
				return (Temporal.class.isAssignableFrom(javaType) || Date.class.isAssignableFrom(javaType))
						&& javaType != java.time.OffsetDateTime.class && javaType != java.time.ZonedDateTime.class
						&& javaType != java.time.Instant.class;
			}
			if (isNumeric()) {
				return Number.class.isAssignableFrom(javaType) || javaType == Boolean.class
						|| (javaType.isPrimitive() && javaType != char.class);
			}
			if (dataType.equals("RAW") || dataType.equals("BLOB")) {
				return javaType == byte[].class;
			}
			return true;
		}

		public boolean isNumeric() {
			return dataType.equals("NUMBER") || dataType.equals("FLOAT") || dataType.contains("BINARY_")
					|| dataType.contains("PLS_INTEGER");
		}

		/**
		 * How permissive this argument is for a Java parameter: lower is more restrictive, and among
		 * overloaded versions the most restrictive one wins. Only boolean has an ordering:
		 * {@code BOOLEAN} is exact, a number only takes 1 and 0, and a text accepts anything. The
		 * other pairs tie on purpose: for {@code NUMBER} versus {@code BINARY_INTEGER}, the version
		 * that runs is the one Oracle picks from the type sent, and here we could disagree with it.
		 *
		 * <p>With {@link PlsqlBoolean}, the parameter declared that it wants a literal, so
		 * {@code BOOLEAN} comes last, and the literals themselves say whether they are numbers or
		 * text.
		 */
		int permissivenessFor(Parameter parameter, Class<?> javaType) {
			if (javaType != boolean.class && javaType != Boolean.class) {
				return 0;
			}
			PlsqlBoolean literals = parameter.getAnnotation(PlsqlBoolean.class);
			if (literals == null) {
				return isBoolean() ? 0 : isNumeric() ? 1 : 2;
			}
			if (isBoolean()) {
				return 2;
			}
			boolean numericLiterals = isNumber(literals.trueValue()) && isNumber(literals.falseValue());
			if (isNumeric()) {
				return numericLiterals ? 0 : 3;
			}
			return numericLiterals ? 1 : 0;
		}

		private static boolean isNumber(String text) {
			try {
				new BigDecimal(text);
				return true;
			} catch (NumberFormatException e) {
				return false;
			}
		}

		/** With {@code @PlsqlType}, the class says which type is its own; without it, only the shape decides. */
		private boolean acceptsComposite(Class<?> javaType) {
			if (javaType.isPrimitive() || javaType.isEnum() || javaType.isArray()
					|| Number.class.isAssignableFrom(javaType) || CharSequence.class.isAssignableFrom(javaType)
					|| Boolean.class == javaType || Temporal.class.isAssignableFrom(javaType)
					|| Date.class.isAssignableFrom(javaType) || Collection.class.isAssignableFrom(javaType)
					|| Map.class.isAssignableFrom(javaType)) {
				return false;
			}
			PlsqlType declared = javaType.getAnnotation(PlsqlType.class);
			if (declared == null) {
				return true;
			}
			CompositeType type = compositeType(null);
			String declaredName = normalizeTypeName(declared.value());
			return declaredName.equals(type.simpleName()) || declaredName.equals(type.bindName());
		}

		@Override
		public String toString() {
			return name + " " + dataType + (typeName == null ? "" : "(" + typeName + ")");
		}
	}

	/** Where a routine is, after synonyms: its owner, its package, {@code null} outside one, and its name. */
	public record Target(String owner, String packageName, String routine) {
	}

	/** Where {@code routineName}, of {@code packageName} or outside a package when it is {@code null}, is. */
	public static Target target(Dictionary dictionary, Names names, String schema, String packageName,
			String routineName) {
		boolean standalone = packageName == null;
		ObjectName resolved = resolve(dictionary, names, schema, standalone ? routineName : packageName, standalone);
		// outside a package, the synonym points to the routine itself, and it is the routine that gets the real name
		return standalone ? new Target(resolved.owner(), null, resolved.name())
				: new Target(resolved.owner(), resolved.name(), routineName);
	}

	/** Reads the signature of one routine, and describes it for {@code method}: see {@link #of}. */
	public static RoutineMetadata read(Dictionary dictionary, Names names, String schema, String packageName,
			String routineName, Method method, PlsqlConverters converters, boolean byName) {
		Target target = target(dictionary, names, schema, packageName, routineName);
		RoutineSignatures signatures = dictionary.signatures(target.owner(), target.packageName(),
				List.of(target.routine()));
		return of(dictionary, target, signatures, method, converters, byName);
	}

	/**
	 * The routine at {@code target}, as {@code signatures} list it, in the version that fits {@code method}; or fails
	 * saying what exists.
	 *
	 * <p>A routine without arguments has no row in {@code ALL_ARGUMENTS}, so its existence is
	 * confirmed in {@code ALL_PROCEDURES}; otherwise a wrong name would pass as "a routine with zero
	 * arguments" and only blow up on the call.
	 */
	public static RoutineMetadata of(Dictionary dictionary, Target target, RoutineSignatures signatures,
			Method method, PlsqlConverters converters, boolean byName) {
		String routine = target.routine();
		java.util.Optional<RoutineSignatures.Listing> listing = signatures.of(routine);
		if (listing.isEmpty()) {
			requireRoutineExists(dictionary, new ObjectName(target.owner(), target.packageName()), routine);
			RoutineMetadata empty = new RoutineMetadata(target.owner(), target.packageName(), routine, List.of(),
					false);
			return byName ? empty.boundByName(method) : empty;
		}
		List<RoutineMetadata> routines = new ArrayList<>(listing.get().versions().values().stream()
				.map(arguments -> new RoutineMetadata(target.owner(), target.packageName(), routine, arguments, false))
				.toList());
		// a procedure without arguments does not show up in ALL_ARGUMENTS, not even as one version of an overload
		if (listing.get().counted() > routines.size()) {
			routines.add(new RoutineMetadata(target.owner(), target.packageName(), routine, List.of(), false));
		}
		if (byName) {
			return chooseByName(routines, method, converters);
		}
		return routines.size() == 1 ? routines.get(0) : chooseVersion(routines, method, converters);
	}

	/**
	 * With a single version, the error says which name did not match. With several, only those that
	 * match by name qualify, and the choice among them follows the same rule as binding by position.
	 */
	private static RoutineMetadata chooseByName(List<RoutineMetadata> versions, Method method,
			PlsqlConverters converters) {
		if (versions.size() == 1) {
			return versions.get(0).boundByName(method);
		}
		List<RoutineMetadata> bound = new ArrayList<>();
		List<String> refusals = new ArrayList<>();
		for (RoutineMetadata version : versions) {
			try {
				bound.add(version.boundByName(method));
			} catch (PlsqlMappingException e) {
				refusals.add(e.getMessage());
			}
		}
		if (bound.isEmpty()) {
			throw new PlsqlMappingException("no version of " + versions.get(0).qualifiedName()
					+ " matches by name: " + String.join("; ", new java.util.LinkedHashSet<>(refusals)));
		}
		return bound.size() == 1 ? bound.get(0) : chooseVersion(bound, method, converters);
	}

	/**
	 * This version with its parameters in the order of the Java method, found by name. From here on,
	 * {@link #parameters()} returns the Oracle parameter of each Java parameter, at the same position,
	 * and the rest of the library does not need to know how the binding was done.
	 */
	private RoutineMetadata boundByName(Method method) {
		List<Argument> params = parameters();
		Map<String, Argument> byOracleName = new LinkedHashMap<>();
		for (Argument param : params) {
			byOracleName.put(param.name(), param);
		}
		List<Argument> bound = new ArrayList<>();
		returnValue().ifPresent(bound::add);
		Map<String, String> javaNameOf = new LinkedHashMap<>();
		for (Parameter parameter : method.getParameters()) {
			String oracleName = oracleNameOf(method, parameter);
			String javaName = javaNameOf(parameter);
			Argument param = byOracleName.get(oracleName);
			if (param == null) {
				throw new PlsqlMappingException("parameter " + javaName + " looks for "
						+ oracleName + ", which " + qualifiedName() + " does not have. Parameters: " + params
						+ ". Use @PlsqlParam with the Oracle name.");
			}
			String other = javaNameOf.putIfAbsent(oracleName, javaName);
			if (other != null) {
				throw new PlsqlMappingException("parameters " + other + " and " + javaName
						+ " both map to " + oracleName + " of " + qualifiedName());
			}
			bound.add(param);
		}
		List<String> missing = params.stream()
				.filter(param -> !javaNameOf.containsKey(param.name()) && !param.defaulted())
				.map(Argument::name)
				.toList();
		if (!missing.isEmpty()) {
			throw new PlsqlMappingException(qualifiedName() + " requires " + missing
					+ ", which has no DEFAULT, and the method declares no parameter for it. Parameters: " + params);
		}
		return new RoutineMetadata(schema, packageName, routineName, bound, true);
	}

	/** The Oracle name: the one from {@link PlsqlParam}, or the Java name in SNAKE_CASE, like the method's. */
	/** The Oracle name a Java parameter binds to, by name: its {@code @PlsqlParam}, or its Java name in Oracle's style. */
	public static String oracleNameOf(Method method, Parameter parameter) {
		String name = BlockMetadata.nameOf(method, parameter);
		return parameter.isAnnotationPresent(PlsqlParam.class) ? normalize(name) : toRoutineName(name);
	}

	/** How to refer to the parameter in a message: by its Java name, which is what people search for in the code. */
	private static String javaNameOf(Parameter parameter) {
		PlsqlParam named = parameter.getAnnotation(PlsqlParam.class);
		if (parameter.isNamePresent()) {
			return parameter.getName();
		}
		return "@PlsqlParam(\"" + named.value() + "\")";
	}

	/**
	 * Chooses the version of an overloaded routine from the Java method's signature, which is what is
	 * known before there are values: the choice cannot depend on an argument being null. When in
	 * doubt it fails, instead of calling a version picked by luck.
	 */
	private static RoutineMetadata chooseVersion(List<RoutineMetadata> versions, Method method,
			PlsqlConverters converters) {
		List<RoutineMetadata> candidates = versions.stream().filter(version -> version.fits(method, converters))
				.toList();

		if (candidates.size() == 1) {
			return candidates.get(0);
		}
		List<RoutineMetadata> winners = candidates.stream()
				.filter(version -> candidates.stream()
						.allMatch(other -> other == version || version.isMoreRestrictiveThan(other, method, converters)))
				.toList();
		if (winners.size() == 1) {
			return winners.get(0);
		}
		String qualifiedName = versions.get(0).qualifiedName();
		String all = versions.stream().map(RoutineMetadata::signature).collect(Collectors.joining(", "));
		if (candidates.isEmpty()) {
			throw new PlsqlMappingException("no version of " + qualifiedName + " matches the method: " + all);
		}
		throw new PlsqlMappingException("more than one version of " + qualifiedName + " matches the method: "
				+ candidates.stream().map(RoutineMetadata::signature).collect(Collectors.joining(", "))
				+ ". Annotate the type with @PlsqlType or call the desired version with @PlsqlBlock.");
	}

	/**
	 * The same rule Java uses for overloaded methods: at least as restrictive as the other one in
	 * every parameter, and more restrictive in at least one. Without it, {@code f(BOOLEAN, VARCHAR2)}
	 * versus {@code f(VARCHAR2, BOOLEAN)} would have an arbitrary winner.
	 */
	private boolean isMoreRestrictiveThan(RoutineMetadata other, Method method, PlsqlConverters converters) {
		Parameter[] javaParameters = method.getParameters();
		boolean moreRestrictive = false;
		for (int i = 0; i < javaParameters.length; i++) {
			Class<?> sent = sentTypeOf(method, javaParameters[i], converters);
			int mine = parameters().get(i).permissivenessFor(javaParameters[i], sent);
			int theirs = other.parameters().get(i).permissivenessFor(javaParameters[i], sent);
			if (mine > theirs) {
				return false;
			}
			moreRestrictive |= mine < theirs;
		}
		return moreRestrictive;
	}

	/** Whether this version fits the method: kind of routine, number and types of parameters. */
	private boolean fits(Method method, PlsqlConverters converters) {
		if ((ReturnType.of(method) != void.class) != returnValue().isPresent()) {
			return false;
		}
		Parameter[] javaParameters = method.getParameters();
		List<Argument> params = parameters();
		if (javaParameters.length < requiredParameterCount() || javaParameters.length > params.size()) {
			return false;
		}
		for (int i = 0; i < javaParameters.length; i++) {
			Argument param = params.get(i);
			Parameter javaParameter = javaParameters[i];
			boolean holder = OutParameters.isDeclaredBy(javaParameter);
			if (holder != param.isOut()) {
				return false;
			}
			if (holder && OutParameters.valueClassOf(javaParameter) == null) {
				return false;
			}
			if (!param.accepts(sentTypeOf(method, javaParameter, converters))) {
				return false;
			}
		}
		return true;
	}

	/** The type that actually goes to the database: the {@code D} of the parameter's converter, if there is a scalar one. */
	static Class<?> sentTypeOf(Method method, Parameter parameter, PlsqlConverters converters) {
		Class<?> declared = declaredTypeOf(parameter);
		AppliedConverter applied = converterOf(method, parameter, converters);
		return applied == null || applied.elementWise() ? declared : applied.databaseType();
	}

	private static Class<?> declaredTypeOf(Parameter parameter) {
		return OutParameters.isDeclaredBy(parameter) ? OutParameters.valueClassOf(parameter) : parameter.getType();
	}

	public static AppliedConverter converterOf(Method method, Parameter parameter, PlsqlConverters converters) {
		boolean holder = OutParameters.isDeclaredBy(parameter);
		java.lang.reflect.Type generic = holder ? OutParameters.valueTypeOf(parameter) : parameter.getParameterizedType();
		return ConverterLookup.find(converters, parameter.getAnnotation(PlsqlConvert.class),
				declaredTypeOf(parameter), elementClassOf(generic), "parameter " + parameter.getName());
	}

	public static AppliedConverter converterOfReturn(Method method, PlsqlConverters converters) {
		return ConverterLookup.find(converters, method.getAnnotation(PlsqlConvert.class),
				ReturnType.of(method), elementClassOf(ReturnType.genericOf(method)), "the return value");
	}

	/** A package or, outside a package, a standalone routine: the object a synonym can point to. */
	/**
	 * Where a proxy's names were found, so that its methods do not each ask again (#169): the session's
	 * {@code current_schema}, and each package or standalone routine, through its synonyms. One per proxy, as its
	 * other caches: the first method described resolves the name, and the others use it, so that a proxy's methods
	 * never resolve the same name to two places, whichever session described each. A name that was not found is not
	 * kept, since it may be created later, and a call that fails as if the routine had changed clears them (#165).
	 */
	public static final class Names {

		private record Key(String schema, String name, boolean standalone) {
		}

		private volatile String currentSchema;
		private final ConcurrentMap<Key, ObjectName> found = new ConcurrentHashMap<>();

		String currentSchema(Dictionary dictionary) {
			String schema = currentSchema;
			if (schema == null) {
				schema = dictionary.currentSchema();
				currentSchema = schema;
			}
			return schema;
		}

		/** Forgets them all: the next describe resolves the names again. */
		public void clear() {
			currentSchema = null;
			found.clear();
		}
	}

	private record ObjectName(String owner, String name) {
	}

	/**
	 * Where the package, or the standalone routine, actually is: see {@link #resolveAll}. A name not found resolves to
	 * itself, in the schema, so that the error names it there.
	 */
	private static ObjectName resolve(Dictionary dictionary, Names names, String schema, String objectName,
			boolean standalone) {
		ObjectName found = resolveAll(dictionary, names, schema, List.of(objectName), standalone).get(objectName);
		return found != null ? found : new ObjectName(schema != null ? schema : names.currentSchema(dictionary),
				objectName);
	}

	/**
	 * Where each standalone routine of {@code routineNames} is, after synonyms: only those found, by the name the
	 * method declares (#169).
	 */
	public static Map<String, Target> targets(Dictionary dictionary, Names names, String schema,
			java.util.Collection<String> routineNames) {
		Map<String, Target> targets = new LinkedHashMap<>();
		resolveAll(dictionary, names, schema, routineNames, true)
				.forEach((routine, at) -> targets.put(routine, new Target(at.owner(), null, at.name())));
		return targets;
	}

	/**
	 * Where each of {@code objectNames} actually is, as Oracle resolves it: an object in the schema itself takes
	 * precedence, then a private synonym and, without an explicit schema, a public one, through synonyms of synonyms.
	 * This has to be done here because {@code ALL_ARGUMENTS} only knows the real name. Only the names found are
	 * returned, and kept in {@code names}.
	 *
	 * <p>Oracle resolves them itself, with {@code DBMS_UTILITY.NAME_RESOLVE}, all of them in one round trip, up to
	 * {@value #NAMES_PER_RESOLVE} at a time (#174): the order is Oracle's by definition, not this library's rendering
	 * of it. It replaced rounds of {@code ALL_OBJECTS} and {@code ALL_SYNONYMS}, a query per owner each, and
	 * {@code ALL_SYNONYMS} costs about 50 ms a query on 21c. A name does not resolve when Oracle says it does not exist
	 * or is not accessible ({@code ORA-06564}), it is a cycle of synonyms ({@code ORA-00980}) or not something PL/SQL
	 * calls ({@code ORA-04047}), it is remote, or it is not of the kind asked for: a package for a standalone routine,
	 * or the other way round. {@link Dictionary#resolve} raises any other error, as a failure reading the dictionary.
	 */
	private static Map<String, ObjectName> resolveAll(Dictionary dictionary, Names names, String schema,
			java.util.Collection<String> objectNames, boolean standalone) {
		Map<String, ObjectName> found = new LinkedHashMap<>();
		List<String> pending = new ArrayList<>();
		for (String objectName : new java.util.LinkedHashSet<>(objectNames)) {
			ObjectName known = names.found.get(new Names.Key(schema, objectName, standalone));
			if (known != null) {
				found.put(objectName, known);
			} else {
				pending.add(objectName);
			}
		}
		for (int from = 0; from < pending.size(); from += NAMES_PER_RESOLVE) {
			List<String> chunk = pending.subList(from, Math.min(pending.size(), from + NAMES_PER_RESOLVE));
			// quoted, so that Oracle takes each name as the library normalized it, and the schema too when declared
			List<String> quoted = chunk.stream()
					.map(n -> schema != null ? quote(schema) + "." + quote(n) : quote(n)).toList();
			List<Dictionary.Resolution> resolutions = dictionary.resolve(quoted);
			for (int k = 0; k < chunk.size(); k++) {
				ObjectName at = resolved(resolutions.get(k), standalone);
				if (at != null) {
					found.put(chunk.get(k), at);
					names.found.put(new Names.Key(schema, chunk.get(k), standalone), at);
				}
			}
		}
		return found;
	}

	/** How many names one call of {@code NAME_RESOLVE}'s block takes: 9 binds each. */
	private static final int NAMES_PER_RESOLVE = 50;

	/** The object {@code resolution} names, if it is of the kind asked for; or {@code null}. */
	private static ObjectName resolved(Dictionary.Resolution resolution, boolean standalone) {
		if (!resolution.resolved() || resolution.dblink() != null) {
			return null;
		}
		boolean routine = (resolution.type() == PROCEDURE || resolution.type() == FUNCTION) && resolution.part1() == null;
		boolean pkg = resolution.type() == PACKAGE && resolution.part2() == null;
		if (standalone && routine) {
			return new ObjectName(resolution.schema(), resolution.part2());
		}
		return !standalone && pkg ? new ObjectName(resolution.schema(), resolution.part1()) : null;
	}

	/** {@code NAME_RESOLVE}'s {@code part1_type}: a standalone procedure, a standalone function, a package. */
	private static final int PROCEDURE = 7;
	private static final int FUNCTION = 8;
	private static final int PACKAGE = 9;

	private static String quote(String identifier) {
		return '"' + identifier + '"';
	}

	private static void requireRoutineExists(Dictionary dictionary, ObjectName target, String routineName) {
		String owner = target.owner();
		String packageName = target.name();
		if (dictionary.versionsOf(owner, packageName, routineName) > 0) {
			return;
		}
		if (packageName == null) {
			throw new PlsqlMappingException("standalone routine " + owner + "." + routineName
					+ " does not exist or is not visible to this user (@PlsqlSchema maps the functions and"
					+ " procedures outside a package)");
		}
		List<String> available = dictionary.routinesOf(owner, packageName);

		if (available.isEmpty()) {
			throw new PlsqlMappingException("package " + owner + "." + packageName
					+ " does not exist or is not visible to this user");
		}
		throw new PlsqlMappingException("routine " + owner + "." + packageName + "." + routineName
				+ " does not exist. Available: " + String.join(", ", available));
	}

	/** This version as a message shows it: {@code (P_VALUE NUMBER) RETURN VARCHAR2}, or without RETURN for a procedure. */
	private String signature() {
		String parameters = parameters().stream().map(Argument::toString).collect(Collectors.joining(", ", "(", ")"));
		return returnValue().map(value -> parameters + " RETURN " + value.dataType()).orElse(parameters);
	}

	/** The return value, when it is a function. */
	public java.util.Optional<Argument> returnValue() {
		return arguments.stream().filter(Argument::isReturnValue).findFirst();
	}

	/**
	 * The formal parameters, in the order Java binds to them: the routine's or, with
	 * {@link PlsqlBind#BY_NAME}, the method's.
	 */
	public List<Argument> parameters() {
		return arguments.stream().filter(a -> !a.isReturnValue()).toList();
	}

	/** How many parameters are required: all but those with DEFAULT at the end of the list. */
	int requiredParameterCount() {
		List<Argument> params = parameters();
		int required = params.size();
		while (required > 0 && params.get(required - 1).defaulted()) {
			required--;
		}
		return required;
	}

	/** For messages and the log: the names as the dictionary spells them, without quotes. */
	public String qualifiedName() {
		return schema + "." + (packageName == null ? "" : packageName + ".") + routineName;
	}

	/** For the call: a name created in quotes, such as {@code "fixtureLowercase"}, only matches in quotes. */
	private String callName() {
		return quoted(schema) + "." + (packageName == null ? "" : quoted(packageName) + ".") + quoted(routineName);
	}

	/**
	 * {@code {call PKG.ROUTINE(P_A => ?, P_B => ?)}} for a procedure, {@code {? = call PKG.FUNC(P_A => ?)}} for a
	 * function, without the package for a standalone routine, with as many arguments as the caller supplied, not as
	 * many as the routine declares: Oracle fills in a parameter left out with its DEFAULT.
	 *
	 * <p><b>Always in named notation (#159).</b> Binding by name, the parameters are in the Java order, each found by
	 * its name. Binding by position, they are the routine's first ones as this description read them, so each value
	 * goes to the parameter that was at its position when the proxy checked the method, by that parameter's name. A
	 * call by position, {@code F(?, ?)}, matched the order of the routine at each execution instead: after a deploy
	 * that inserts a parameter with DEFAULT in the middle, or swaps two of the same type, it sent the values to other
	 * parameters with no error, and a renamed parameter took the value meant for the old one. In named notation those
	 * get the values the method was written for, or fail without running, after which the routine is described again.
	 * Measured on 23ai, 21c and 19c: where a call by position works, a named one gives the same result, overloads
	 * and parameters left out at the end included.
	 *
	 * <p>An argument of a type that the driver does not know takes the placeholder and the return value of its
	 * {@link Carrier}, such as {@code JSON(?)} and {@code JSON_SERIALIZE(... RETURNING CLOB)} for Oracle's JSON type
	 * with driver 19: the database converts the value, and the driver sees only a type it knows.
	 */
	public String callStatement(int providedArgumentCount, boolean expectsResult, Carriers carriers) {
		String prefix = expectsResult ? "{? = call " : "{call ";
		List<Argument> params = parameters();
		String call = callName();
		if (providedArgumentCount > 0) {
			call += java.util.stream.IntStream.range(0, providedArgumentCount)
					.mapToObj(i -> quoted(params.get(i).name()) + " => "
							+ carriers.of(params.get(i)).map(Carrier::placeholder).orElse("?"))
					.collect(Collectors.joining(", ", "(", ")"));
		}
		if (expectsResult) {
			java.util.Optional<Carrier> carrier = returnValue().flatMap(carriers::of);
			if (carrier.isPresent()) {
				call = carrier.get().result(call);
			}
		}
		return prefix + call + "}";
	}

	/** A name created in quotes, such as {@code "pValue"}, only matches in quotes. */
	private static String quoted(String name) {
		return name.matches("[A-Z][A-Z0-9_$#]*") ? name : "\"" + name.replace("\"", "\"\"") + "\"";
	}

	/**
	 * The element type of a collection, read from the dictionary; {@code null} when the element is a
	 * scalar.
	 *
	 * <p>The dictionary knows this without help, and knows it for all four forms: a schema-level
	 * collection in {@code ALL_COLL_TYPES}, a package collection in {@code ALL_PLSQL_COLL_TYPES},
	 * each with a composite or scalar element. For {@code TABLE OF <table>%ROWTYPE}, the
	 * {@code elem_type_name} itself already comes with the literal suffix, which is exactly the name
	 * {@code createStruct} accepts.
	 */
	static CompositeType elementTypeOf(TypeDescriptions types, Dictionary dictionary, Argument collection, String schema) {
		return collectionTypeOf(types, dictionary, collection, schema).compositeElement();
	}

	/** The collection of an argument, with what it holds, read from the dictionary. */
	public static CollectionType collectionTypeOf(TypeDescriptions types, Dictionary dictionary, Argument collection, String schema) {
		if (!collection.isCollection()) {
			throw new PlsqlMappingException(collection.name() + " is " + collection.dataType()
					+ ", not a collection");
		}
		String owner = collection.ownerOr(schema);
		return collection.typeSubname() != null
				? collectionTypeOf(types, dictionary, owner, collection.typeName(), collection.typeSubname())
				: collectionTypeOf(types, dictionary, owner, null, collection.typeName());
	}

	/** The index type of a collection, such as {@code PLS_INTEGER}, or {@code null} if it is not {@code INDEX BY}. */
	public static String indexByOf(TypeDescriptions types, Dictionary dictionary, String schema, String container, String typeName) {
		return types.collection(new TypeDescription.TypeName(schema, container, typeName), dictionary).indexBy();
	}

	static String elementTypeNameOf(TypeDescriptions types, Dictionary dictionary, Argument collection, String schema) {
		String owner = collection.ownerOr(schema);
		return collection.typeSubname() != null
				? elementTypeNameOf(types, dictionary, owner, collection.typeName(), collection.typeSubname())
				: elementTypeNameOf(types, dictionary, owner, null, collection.typeName());
	}

	/** The name as the dictionary spells it, including for a scalar element: {@code TIMESTAMP(6) WITH TIME ZONE}. */
	public static String elementTypeNameOf(TypeDescriptions types, Dictionary dictionary, String schema, String container, String typeName) {
		return types.collection(new TypeDescription.TypeName(schema, container, typeName), dictionary).elementTypeName();
	}

	public static Class<?> elementClassOf(java.lang.reflect.Type declared) {
		if (declared instanceof Class<?> type) {
			return type.getComponentType();
		}
		if (declared instanceof java.lang.reflect.ParameterizedType parameterized
				&& parameterized.getActualTypeArguments().length == 1
				&& parameterized.getActualTypeArguments()[0] instanceof Class<?> element) {
			return element;
		}
		return null;
	}

	/**
	 * The same lookup as {@link #elementTypeOf(TypeDescriptions, Dictionary, Argument, String)}, addressed by name: it
	 * serves a collection that is an attribute of a composite type, not a routine parameter. A null
	 * {@code container} means a collection declared in the schema.
	 */
	static CompositeType elementTypeOf(TypeDescriptions types, Dictionary dictionary, String schema, String container, String typeName) {
		return collectionTypeOf(types, dictionary, schema, container, typeName).compositeElement();
	}

	/**
	 * The collection and, level by level, what it holds. An element that is itself a collection, as in
	 * {@code TABLE OF NAME_LIST_T}, has an owner like an OBJECT, so only the {@code typecode} tells them
	 * apart; treated as an OBJECT, it would be looked up for attributes it does not have.
	 */
	public static CollectionType collectionTypeOf(TypeDescriptions types, Dictionary dictionary, String schema, String container, String typeName) {
		java.util.Optional<Dictionary.Element> element = java.util.Optional.ofNullable(
				types.collection(new TypeDescription.TypeName(schema, container, typeName), dictionary).element());
		if (element.isPresent() && element.get().isRef()) {
			throw new PlsqlMappingException("collection " + (container == null ? typeName : container + "." + typeName)
					+ " holds REFs to " + element.get().name() + ", pointers to rows of an object table, which"
					+ " Penitent does not map: pass the objects themselves, or follow the REFs in PL/SQL");
		}
		if (element.isEmpty()) {
			throw new PlsqlMappingException("collection "
					+ (container == null ? typeName : container + "." + typeName) + " in " + schema
					+ " does not exist or is not visible to this user");
		}
		String bindName = CompositeType.qualified(schema, container, typeName);
		String elementOwner = element.get().owner();
		String elementContainer = element.get().packageName();
		String elementName = element.get().name();
		if (isCollection(types, dictionary, schema, elementOwner, elementContainer, elementName)) {
			return new CollectionType(bindName, null, collectionTypeOf(types, dictionary,
					elementOwner != null ? elementOwner : schema, elementContainer, elementName), null);
		}
		CompositeType composite = elementOf(schema, elementOwner, elementContainer, elementName);
		return new CollectionType(bindName, composite, null, composite == null ? elementName : null);
	}

	private static boolean isCollection(TypeDescriptions types, Dictionary dictionary, String schema, String owner, String container,
			String name) {
		String typeOwner = owner != null ? owner : schema;
		if (container != null) {
			return types.typecode(new TypeDescription.TypeName(typeOwner, container, name), dictionary)
					.filter("COLLECTION"::equals).isPresent();
		}
		return owner != null && name != null && !name.endsWith("%ROWTYPE")
				&& types.typecode(new TypeDescription.TypeName(typeOwner, null, name), dictionary)
						.filter("COLLECTION"::equals).isPresent();
	}

	/** The {@code T} of a {@code List<T>} or {@code T[]}, which may itself be generic, as in {@code List<List<String>>}. */
	public static java.lang.reflect.Type elementTypeOf(java.lang.reflect.Type declared) {
		if (declared instanceof Class<?> type) {
			return type.getComponentType();
		}
		if (declared instanceof java.lang.reflect.GenericArrayType array) {
			return array.getGenericComponentType();
		}
		if (declared instanceof java.lang.reflect.ParameterizedType parameterized
				&& parameterized.getActualTypeArguments().length == 1) {
			return parameterized.getActualTypeArguments()[0];
		}
		return null;
	}

	/** The same decision order as {@link Argument#compositeType}, applied to the element. */
	static CompositeType elementOf(String schema, String owner, String container, String name) {
		String elementOwner = owner != null ? owner : schema;
		if (container != null) {
			return new CompositeType(CompositeType.Kind.PLSQL_RECORD, elementOwner, container, name,
					container + "." + name);
		}
		if (name != null && name.endsWith("%ROWTYPE")) {
			return new CompositeType(CompositeType.Kind.ROWTYPE, elementOwner, null,
					name.substring(0, name.length() - "%ROWTYPE".length()), name);
		}
		if (owner != null) {
			return new CompositeType(CompositeType.Kind.OBJECT, owner, null, name, name);
		}
		return null; // scalar: VARCHAR2, NUMBER, DATE...
	}

	public static String toRoutineName(String javaMethodName) {
		StringBuilder sb = new StringBuilder();
		for (char c : javaMethodName.toCharArray()) {
			if (Character.isUpperCase(c) && !sb.isEmpty()) {
				sb.append('_');
			}
			sb.append(Character.toUpperCase(c));
		}
		return sb.toString();
	}

	/**
	 * An Oracle identifier written in an annotation, read the way SQL reads it: unquoted, it becomes upper
	 * case; in double quotes, it keeps its case, as in {@code "fixtureLowercase"}.
	 */
	public static String normalize(String identifier) {
		if (identifier == null) {
			return null;
		}
		if (identifier.length() >= 2 && identifier.startsWith("\"") && identifier.endsWith("\"")) {
			return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
		}
		return identifier.toUpperCase(Locale.ROOT);
	}

	/**
	 * The parts of a dotted name, such as {@code PACKAGE.RECORD}, each one normalized on its own: a dot
	 * inside quotes is part of the name.
	 */
	static List<String> normalizeParts(String qualified) {
		List<String> parts = new ArrayList<>();
		StringBuilder part = new StringBuilder();
		boolean quoted = false;
		for (char c : qualified.toCharArray()) {
			if (c == '"') {
				quoted = !quoted;
			}
			if (c == '.' && !quoted) {
				parts.add(normalize(part.toString()));
				part.setLength(0);
			} else {
				part.append(c);
			}
		}
		parts.add(normalize(part.toString()));
		return parts;
	}

	/**
	 * A type name as {@link PlsqlType} or {@link PlsqlBlock#resultType()} declares it, normalized part by
	 * part, and with the {@code %ROWTYPE} suffix, which is never quoted, in upper case.
	 */
	public static String normalizeTypeName(String declared) {
		String suffix = "";
		if (declared.toUpperCase(Locale.ROOT).endsWith("%ROWTYPE")) {
			suffix = "%ROWTYPE";
			declared = declared.substring(0, declared.length() - suffix.length());
		}
		return String.join(".", normalizeParts(declared)) + suffix;
	}
}
