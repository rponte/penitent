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
					throw new PlsqlMappingException(method.getName()
							+ ": the block uses :result more than once; it is the only output and there can be only one");
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
			throw new PlsqlMappingException(method.getName() + ": :result is a cursor; declare List<T>, not "
					+ returned.getSimpleName());
		}
		boolean many = java.util.Collection.class.isAssignableFrom(returned)
				|| (returned.isArray() && returned != byte[].class);
		if (many && !cursorResult && block.resultType().isBlank()) {
			throw new PlsqlMappingException(method.getName() + ": :result is a " + returned.getSimpleName()
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
				throw new PlsqlMappingException(method.getName() + ": a block does not accept PlsqlOut;"
						+ " its only output is :result");
			}
		}

		boolean returnsValue = ReturnType.of(method) != void.class;
		if (returnsValue && resultPosition < 0) {
			throw new PlsqlMappingException(method.getName() + " declares return type "
					+ ReturnType.of(method).getSimpleName() + ", but the block has no :result");
		}
		if (!returnsValue && resultPosition >= 0) {
			throw new PlsqlMappingException(method.getName()
					+ " is void, but the block has :result; declare the return type or remove the :result");
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
			throw new PlsqlMappingException(method.getName() + ": the block uses " + unmatched
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
			throw new PlsqlMappingException(method.getName()
					+ ": parameter names are not in the bytecode (compile with -parameters)"
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

		public String qualifiedBindName() {
			return isCollection() ? collection.bindName() : composite.qualifiedBindName();
		}
	}

	/**
	 * Resolves the name in the session's schema, following the table in {@link CompositeType}:
	 * {@code TYPE}, {@code PACKAGE.RECORD} or {@code TABLE%ROWTYPE}. Only the {@code typecode} tells a
	 * collection apart from an OBJECT or a RECORD; a name the dictionary does not know is left to
	 * {@link io.github.rponte.penitent.internal.binding.TypeBinding}, which says it does not exist.
	 */
	public static DeclaredType declaredType(Dictionary dictionary, String schema, String declared) {
		String typeName = RoutineMetadata.normalizeTypeName(declared);
		if (typeName.endsWith("%ROWTYPE")) {
			return new DeclaredType(new CompositeType(CompositeType.Kind.ROWTYPE, schema, null,
					typeName.substring(0, typeName.length() - "%ROWTYPE".length()), typeName), null);
		}
		List<String> parts = RoutineMetadata.normalizeParts(declared);
		if (parts.size() == 2) {
			String container = parts.get(0);
			String name = parts.get(1);
			boolean collection = dictionary.strings("""
					select typecode from all_plsql_types
					 where owner = ? and package_name = ? and type_name = ?
					""", schema, container, name).contains("COLLECTION");
			return collection
					? new DeclaredType(null, RoutineMetadata.collectionTypeOf(dictionary, schema, container, name))
					: new DeclaredType(new CompositeType(CompositeType.Kind.PLSQL_RECORD, schema, container, name,
							container + "." + name), null);
		}
		if (parts.size() != 1) {
			throw new PlsqlMappingException("type " + declared + ": declare TYPE, PACKAGE.RECORD or TABLE%ROWTYPE,"
					+ " resolved in the session's schema");
		}
		String name = parts.get(0);
		boolean collection = dictionary.strings("select typecode from all_types where owner = ? and type_name = ?",
				schema, name).contains("COLLECTION");
		return collection
				? new DeclaredType(null, RoutineMetadata.collectionTypeOf(dictionary, schema, null, name))
				: new DeclaredType(new CompositeType(CompositeType.Kind.OBJECT, schema, null, name, name), null);
	}
}
