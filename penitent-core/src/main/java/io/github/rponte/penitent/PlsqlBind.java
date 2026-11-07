package io.github.rponte.penitent;

/**
 * How a method's parameters bind to a routine's. It applies to the whole interface, through
 * {@link PlsqlPackage#bind()} or {@link PlsqlSchema#bind()}; an interface that does not declare it
 * uses {@link PlsqlSettings#bind()}, which is {@link #BY_POSITION} unless changed.
 */
public enum PlsqlBind {

	/**
	 * Not declared: the interface uses {@link PlsqlSettings#bind()}. It is the annotations' default, so
	 * that a setting can apply to every interface that does not choose; it is not a valid setting itself.
	 */
	DEFAULT,

	/**
	 * The Java parameter at position <i>i</i> goes to the Oracle parameter at position <i>i</i>, as in
	 * {@code ROUTINE(?, ?)}. Only parameters with {@code DEFAULT} at the end of the list can be
	 * omitted, and if two parameters of the same type swap positions in the database, nothing fails.
	 */
	BY_POSITION,

	/**
	 * Each Java parameter goes to the Oracle parameter with the same name, as in
	 * {@code ROUTINE(P_A => ?, P_C => ?)}. The name is the Java parameter's in SNAKE_CASE, like the
	 * method's, or the one from {@link PlsqlParam}: there is no prefix convention, because that varies
	 * from project to project. It requires compiling with {@code -parameters}, or {@link PlsqlParam}
	 * on every parameter.
	 *
	 * <p>A Java parameter with no match, two with the same match, or an Oracle parameter without
	 * {@code DEFAULT} and with no match are errors on the first call. A parameter with
	 * {@code DEFAULT} can be omitted anywhere in the list: the method simply does not declare it.
	 */
	BY_NAME
}
