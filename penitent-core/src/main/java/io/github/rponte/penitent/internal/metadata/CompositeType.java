package io.github.rponte.penitent.internal.metadata;

/**
 * An Oracle composite type, with the name JDBC addresses it by and the place where its attributes are
 * described in the dictionary.
 *
 * <p>Three forms, three dictionary views, and the {@code bindName} of each one is what
 * {@code createStruct}/{@code registerOutParameter} accepts:
 *
 * <table class="striped">
 *   <caption>Composite types and where the dictionary describes them</caption>
 *   <tr><th>form</th><th>bindName</th><th>attributes in</th></tr>
 *   <tr><td>OBJECT</td><td>{@code TYPE}</td><td>{@code ALL_TYPE_ATTRS}</td></tr>
 *   <tr><td>package RECORD</td><td>{@code PACKAGE.RECORD}</td><td>{@code ALL_PLSQL_TYPE_ATTRS}</td></tr>
 *   <tr><td>{@code %ROWTYPE}</td><td>{@code TABLE%ROWTYPE}, after the owner in quotes</td><td>{@code ALL_TAB_COLUMNS}</td></tr>
 * </table>
 *
 * <p>The last two work since Oracle 12.1. Table A-5 of the JDBC guide says
 * {@code RECORD → no}, which is true in the sense that there is no automatic mapping and no
 * {@code Types.RECORD}; but the type is addressable, and that is enough.
 *
 * <p>A collection follows the <b>same</b> qualification rule, and all three forms also work as its
 * element: {@code TABLE OF <record>} and {@code TABLE OF <table>%ROWTYPE} cross JDBC as an
 * {@code Array} of {@code Struct} in both directions (see {@code RecordAndRowtypeTest}). Only the
 * <b>addressing</b> moves: the collection's name comes from {@code ALL_ARGUMENTS}, the element's from
 * {@code ALL_COLL_TYPES}/{@code ALL_PLSQL_COLL_TYPES}.
 */
public record CompositeType(Kind kind, String schema, String container, String simpleName, String bindName) {

	public enum Kind {
		OBJECT, PLSQL_RECORD, ROWTYPE
	}

	/**
	 * The name prefixed with its owner, which is what the driver needs: without it, the name is
	 * looked up in the session's schema, which is not the type's schema when the package lives in
	 * another schema.
	 *
	 * <p>A {@code %ROWTYPE} has the owner in quotes, {@code "SCHEMA".TABLE%ROWTYPE}, so that it binds on
	 * every driver version. Before driver 23, the driver puts the whole name in quotes before the database
	 * resolves it, and {@code "SCHEMA"."TABLE%ROWTYPE"} is a type that does not exist; a name that already
	 * starts with a quote goes as it is, and the database reads the suffix. Driver 23 accepts both
	 * spellings. The owner comes from the dictionary with its exact case, which the quotes keep.
	 *
	 * <p>A name with a part that needs quotes, such as {@code "Two Words_T"}, has every part in quotes, the owner
	 * included, as {@link #qualified} does: that is the one spelling every driver resolves. A plain name keeps the
	 * spelling above. {@code DriverAddressingTest} pins each driver's behavior.
	 */
	public String qualifiedBindName() {
		if (kind == Kind.ROWTYPE) {
			return ordinary(schema) && ordinary(simpleName) ? "\"" + schema + "\"." + bindName
					: quoted(schema) + "." + quoted(simpleName) + "%ROWTYPE";
		}
		return qualified(schema, container, simpleName);
	}

	/**
	 * {@code owner.container.name}, without the container when it is null, as the driver addresses an object type,
	 * a package RECORD or a collection. The parts come from the dictionary with their exact case. Plain, they go as
	 * they are. When one of them needs quotes, every one goes in quotes. Before driver 23, the driver puts in quotes
	 * a name that does not start with one, as a whole, and driver 23 reads an unquoted part as it is, so neither a
	 * name without quotes nor one with quotes on that part alone resolves on every driver.
	 */
	public static String qualified(String owner, String container, String name) {
		boolean plain = ordinary(owner) && (container == null || ordinary(container)) && ordinary(name);
		if (plain) {
			return owner + "." + (container == null ? "" : container + ".") + name;
		}
		return quoted(owner) + "." + (container == null ? "" : quoted(container) + ".") + quoted(name);
	}

	/** An identifier that SQL reads the same without quotes: upper case, starting with a letter. */
	private static boolean ordinary(String part) {
		return part.matches("[A-Z][A-Z0-9_$#]*");
	}

	private static String quoted(String part) {
		return "\"" + part + "\"";
	}

	@Override
	public String toString() {
		return bindName + " (" + kind + ")";
	}
}
