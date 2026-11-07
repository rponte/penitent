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
 *   <tr><td>{@code %ROWTYPE}</td><td>{@code TABLE%ROWTYPE}</td><td>{@code ALL_TAB_COLUMNS}</td></tr>
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
	 */
	public String qualifiedBindName() {
		return schema + "." + bindName;
	}

	@Override
	public String toString() {
		return bindName + " (" + kind + ")";
	}
}
