package io.github.rponte.penitent.internal.metadata;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlParam;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.metadata.Dictionary.ObjectName;

/**
 * A {@link PlsqlBlock} block translated into what JDBC understands: text with {@code ?} and the order
 * in which the names appear.
 *
 * <p>Each <b>occurrence</b> of {@code :name} is a bind position, so a repeated name is bound more
 * than once. That is what JDBC requires, and it avoids the surprise of the second use coming in
 * empty.
 *
 * <p>{@code :result} is a cursor when {@code resultType} says {@code SYS_REFCURSOR} or, without
 * {@code resultType}, when the code opens it, with {@code OPEN :result FOR}. Any other
 * {@code resultType} is a composite or a collection, and the dictionary says which.
 */
public record BlockMetadata(String sql, List<String> placeholders, String resultType, int resultPosition,
		boolean cursorResult) {

	/** A letter or {@code _} after the colon: that is what tells {@code :name} apart from {@code :=}. */
	private static final Pattern PLACEHOLDER = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

	/**
	 * What Oracle does not read as code, and where a {@code :} is not a placeholder: comments,
	 * alternative-quoting literals, ordinary literals and quoted identifiers.
	 */
	private static final Pattern NOT_CODE = Pattern.compile(
			"--[^\\n]*+"
					+ "|/\\*.*?\\*/"
					+ "|(?<![A-Za-z0-9_$#])[nN]?[qQ]'(?:\\[.*?\\]|\\{.*?\\}|\\(.*?\\)|<.*?>|([^\\s\\[{(<]).*?\\1)'"
					+ "|'[^']*+(?:''[^']*+)*+'"
					+ "|\"[^\"]*+\"",
			Pattern.DOTALL);

	static final String RESULT = "result";

	static final String CURSOR = "SYS_REFCURSOR";

	/** Only in code: in a comment or a literal, it opens nothing. */
	private static final Pattern OPENS_RESULT = Pattern.compile("(?i)\\bopen\\s+:result\\s+for\\b");

	public static BlockMetadata of(Method method, PlsqlBlock block) {
		String text = block.value();
		StringBuilder sql = new StringBuilder();
		List<String> placeholders = new ArrayList<>();
		int end = 0;

		StringBuilder code = new StringBuilder();
		Matcher notCode = NOT_CODE.matcher(text);
		while (notCode.find()) {
			appendCode(text.substring(end, notCode.start()), sql, placeholders);
			code.append(text, end, notCode.start()).append(' ');
			sql.append(notCode.group());
			end = notCode.end();
		}
		appendCode(text.substring(end), sql, placeholders);
		code.append(text.substring(end));

		int resultPosition = -1;
		for (int i = 0; i < placeholders.size(); i++) {
			if (RESULT.equalsIgnoreCase(placeholders.get(i))) {
				if (resultPosition >= 0) {
					throw new PlsqlMappingException(
							"the block uses :result more than once; it is the only output and there can be only one");
				}
				resultPosition = i + 1;
			}
		}

		requireConsistentWithSignature(method, placeholders, resultPosition);
		boolean cursorResult = resultPosition >= 0 && (block.resultType().isBlank()
				? OPENS_RESULT.matcher(code).find()
				: CURSOR.equalsIgnoreCase(block.resultType().strip()));
		requireResultTypeToBeKnown(method, block, resultPosition, cursorResult);
		return new BlockMetadata(sql.toString(), List.copyOf(placeholders), block.resultType(), resultPosition,
				cursorResult);
	}

	/**
	 * Without {@code resultType}, the type of {@code :result} comes from Java, and a {@code List} says
	 * neither which collection nor whether it is one: Oracle would answer {@code PLS-00382}. A cursor
	 * needs no name, but becomes a {@code List} and nothing else.
	 */
	private static void requireResultTypeToBeKnown(Method method, PlsqlBlock block, int resultPosition,
			boolean cursorResult) {
		if (resultPosition < 0) {
			return;
		}
		Class<?> returned = ReturnType.of(method);
		if (cursorResult && !List.class.isAssignableFrom(returned)) {
			throw new PlsqlMappingException(":result is a cursor; declare List<T>, not "
					+ returned.getSimpleName());
		}
		boolean many = java.util.Collection.class.isAssignableFrom(returned)
				|| (returned.isArray() && returned != byte[].class);
		if (many && !cursorResult && block.resultType().isBlank()) {
			throw new PlsqlMappingException(":result is a " + returned.getSimpleName()
					+ "; declare resultType with the collection type, as in resultType = \"NAME_LIST_T\", or open it"
					+ " as a cursor with OPEN :result FOR");
		}
	}

	private static void appendCode(String code, StringBuilder sql, List<String> placeholders) {
		Matcher matcher = PLACEHOLDER.matcher(code);
		int end = 0;
		while (matcher.find()) {
			sql.append(code, end, matcher.start()).append('?');
			end = matcher.end();
			placeholders.add(matcher.group(1));
		}
		sql.append(code.substring(end));
	}

	/**
	 * The three ways the block and the signature can disagree, each with its own message. It is worth
	 * spending lines here: for a block there is no dictionary to catch the error later, and what is
	 * left is an {@code ORA-06550} pointing at text the user did not write (ours, with {@code ?}).
	 */
	private static void requireConsistentWithSignature(Method method, List<String> placeholders,
			int resultPosition) {

		for (Parameter parameter : method.getParameters()) {
			if (OutParameters.isDeclaredBy(parameter)) {
				throw new PlsqlMappingException("a block does not accept PlsqlOut;"
						+ " its only output is :result");
			}
		}

		boolean returnsValue = ReturnType.of(method) != void.class;
		if (returnsValue && resultPosition < 0) {
			throw new PlsqlMappingException("the method declares return type "
					+ ReturnType.of(method).getSimpleName() + ", but the block has no :result");
		}
		if (!returnsValue && resultPosition >= 0) {
			throw new PlsqlMappingException(
					"the method is void, but the block has :result; declare the return type or remove the :result");
		}

		Set<String> expected = new LinkedHashSet<>(placeholders);
		expected.remove(RESULT);
		expected.removeIf(RESULT::equalsIgnoreCase);

		List<String> declared = new ArrayList<>();
		for (Parameter parameter : method.getParameters()) {
			declared.add(nameOf(method, parameter));
		}

		List<String> unmatched = expected.stream().filter(n -> !declared.contains(n)).toList();
		if (!unmatched.isEmpty()) {
			throw new PlsqlMappingException("the block uses " + unmatched
					+ ", which matches no parameter. Declared: " + declared
					+ ". Use @PlsqlParam to declare the name.");
		}
	}

	/**
	 * The parameter name comes from {@link PlsqlParam} or from reflection. Without either, the name
	 * would be {@code arg0} (compiled without {@code -parameters}), and then it is better to say so
	 * than to let the binding go wrong silently.
	 */
	public static String nameOf(Method method, Parameter parameter) {
		PlsqlParam named = parameter.getAnnotation(PlsqlParam.class);
		if (named != null) {
			return named.value();
		}
		if (!parameter.isNamePresent()) {
			throw new PlsqlMappingException(
					"parameter names are not in the bytecode (compile with -parameters)"
					+ " or use @PlsqlParam on each one");
		}
		return parameter.getName();
	}

	/**
	 * A type the author of the block named, in {@code resultType} or in {@link PlsqlType} on a
	 * parameter: a composite or a collection, never both. The block has no dictionary entry, but the
	 * type does.
	 */
	public record DeclaredType(CompositeType composite, CollectionType collection) {

		public boolean isCollection() {
			return collection != null;
		}
	}

	/**
	 * Resolves the name as PL/SQL would, following the table in {@link CompositeType}: {@code TYPE},
	 * {@code PACKAGE.TYPE}, {@code SCHEMA.TYPE}, {@code SCHEMA.PACKAGE.TYPE}, {@code TABLE%ROWTYPE} and
	 * {@code SCHEMA.TABLE%ROWTYPE}. A name without a schema is looked up in the session's, then through a private
	 * synonym and a public one; in {@code A.B}, a package {@code A} of the session's schema comes before a schema
	 * {@code A}, as in PL/SQL. Only the {@code typecode} tells a collection apart from an OBJECT or a RECORD; a name the
	 * dictionary does not know is left to {@link io.github.rponte.penitent.internal.binding.TypeBinding}, which says it
	 * does not exist.
	 */
	public static DeclaredType declaredType(TypeDescriptions types, Dictionary dictionary, String schema,
			String declared) {
		String typeName = RoutineMetadata.normalizeTypeName(declared);
		if (typeName.endsWith("%ROWTYPE")) {
			return rowType(types, dictionary, schema, declared.strip().substring(0, declared.strip().length()
					- "%ROWTYPE".length()), typeName);
		}
		List<String> parts = RoutineMetadata.normalizeParts(declared);
		if (parts.size() == 3) {
			ObjectName pkg = dictionary.objectBehind(parts.get(0), parts.get(1), true, "PACKAGE").orElse(null);
			return packageType(types, dictionary, pkg != null ? pkg : new ObjectName(parts.get(0), parts.get(1)), parts.get(2));
		}
		if (parts.size() == 2) {
			ObjectName pkg = dictionary.objectBehind(schema, parts.get(0), false, "PACKAGE").orElse(null);
			if (pkg != null && hasPackageType(types, dictionary, pkg, parts.get(1))) {
				return packageType(types, dictionary, pkg, parts.get(1));
			}
			ObjectName type = dictionary.objectBehind(parts.get(0), parts.get(1), true, "TYPE").orElse(null);
			if (type != null) {
				return schemaType(types, dictionary, type);
			}
			return packageType(types, dictionary, new ObjectName(schema, parts.get(0)), parts.get(1));
		}
		if (parts.size() != 1) {
			throw new PlsqlMappingException("type " + declared + ": declare TYPE, PACKAGE.TYPE, SCHEMA.TYPE,"
					+ " SCHEMA.PACKAGE.TYPE or TABLE%ROWTYPE, with or without its schema");
		}
		ObjectName type = dictionary.objectBehind(schema, parts.get(0), false, "TYPE").orElse(null);
		return schemaType(types, dictionary, type != null ? type : new ObjectName(schema, parts.get(0)));
	}

	private static DeclaredType rowType(TypeDescriptions types, Dictionary dictionary, String schema, String table, String typeName) {
		List<String> parts = RoutineMetadata.normalizeParts(table);
		ObjectName resolved = parts.size() == 2
				? dictionary.objectBehind(parts.get(0), parts.get(1), true, "TABLE", "VIEW").orElse(null)
				: dictionary.objectBehind(schema, parts.get(0), false, "TABLE", "VIEW").orElse(null);
		if (resolved == null) {
			return new DeclaredType(new CompositeType(CompositeType.Kind.ROWTYPE, schema, null,
					typeName.substring(0, typeName.length() - "%ROWTYPE".length()), typeName), null);
		}
		return new DeclaredType(new CompositeType(CompositeType.Kind.ROWTYPE, resolved.owner(), null, resolved.name(),
				resolved.name() + "%ROWTYPE"), null);
	}

	private static boolean hasPackageType(TypeDescriptions types, Dictionary dictionary, ObjectName pkg, String name) {
		return types.typecode(new TypeDescription.TypeName(pkg.owner(), pkg.name(), name), dictionary).isPresent();
	}

	private static DeclaredType packageType(TypeDescriptions types, Dictionary dictionary, ObjectName pkg, String name) {
		boolean collection = types.typecode(new TypeDescription.TypeName(pkg.owner(), pkg.name(), name), dictionary)
				.filter("COLLECTION"::equals).isPresent();
		return collection
				? new DeclaredType(null, RoutineMetadata.collectionTypeOf(types, dictionary, pkg.owner(), pkg.name(), name))
				: new DeclaredType(new CompositeType(CompositeType.Kind.PLSQL_RECORD, pkg.owner(), pkg.name(), name,
						pkg.name() + "." + name), null);
	}

	private static DeclaredType schemaType(TypeDescriptions types, Dictionary dictionary, ObjectName type) {
		boolean collection = types.typecode(new TypeDescription.TypeName(type.owner(), null, type.name()), dictionary)
				.filter("COLLECTION"::equals).isPresent();
		return collection
				? new DeclaredType(null, RoutineMetadata.collectionTypeOf(types, dictionary, type.owner(), null, type.name()))
				: new DeclaredType(new CompositeType(CompositeType.Kind.OBJECT, type.owner(), null, type.name(),
						type.name()), null);
	}
}
