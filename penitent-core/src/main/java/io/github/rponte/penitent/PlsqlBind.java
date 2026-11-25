package io.github.rponte.penitent;

/**
 * How a method's parameters bind to a routine's. It applies to the whole interface, through
 * {@link PlsqlPackage#bind()} or {@link PlsqlSchema#bind()}; an interface that does not declare it
 * uses {@link PlsqlSettings#bind()}, which is {@link #BY_POSITION} unless changed.
 *
 * <p>Both send the call in named notation, {@code ROUTINE(P_A => ?, P_B => ?)}; they differ in where the
 * names come from. By position, the Java interface does not depend on the routine's parameter names, but a
 * change of their order, seen after a restart, can send values to the wrong parameters with no error. By
 * name, it does not depend on their order, but on their names. Oracle recommends named notation for a
 * subprogram defined or maintained by someone else.
 */
public enum PlsqlBind {

	/**
	 * Not declared: the interface uses {@link PlsqlSettings#bind()}. It is the annotations' default, so
	 * that a setting can apply to every interface that does not choose; it is not a valid setting itself.
	 */
	DEFAULT,

	/**
	 * The Java parameter at position <i>i</i> goes to the Oracle parameter at position <i>i</i> of the
	 * routine as the proxy described it, on the method's first call, and the call names that parameter:
	 * {@code ROUTINE(P_A => ?, P_B => ?)}. The Java names do not matter, and need no {@code -parameters}.
	 * Only parameters with {@code DEFAULT} at the end of the list can be omitted.
	 *
	 * <p>While the application runs, a routine whose parameters change order still gets each value in the
	 * parameter it was meant for, by name, and a renamed parameter fails one call without running, after
	 * which the routine is described again. A proxy that describes the routine after the change, as after a
	 * restart, matches the new order: a parameter with {@code DEFAULT} inserted in the middle, or two that
	 * swap places, then take values meant for others, with no error. Bind {@link #BY_NAME} where that
	 * order may change.
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
	 * {@code DEFAULT} can be omitted anywhere in the list: the method simply does not declare it. A change
	 * of the routine's order, while the application runs or after a restart, keeps each value in its
	 * parameter; a renamed parameter is an error until the Java is updated.
	 */
	BY_NAME
}
