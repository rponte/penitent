/**
 * Maps Oracle PL/SQL routines to Java interfaces. Signatures and type attributes come from the
 * database dictionary, so the interface declares only Java types.
 *
 * <pre>{@code
 * @PlsqlPackage("FREIGHT_CALCULATOR")
 * public interface FreightCalculator {
 *     // FUNCTION calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER
 *     BigDecimal calculateFreight(String originState, String destinationState);
 * }
 *
 * FreightCalculator freight = PlsqlPackages.create(FreightCalculator.class, dataSource);
 * }</pre>
 *
 * <ul>
 * <li>{@link io.github.rponte.penitent.PlsqlPackages} creates and validates the implementation.
 * <li>{@link io.github.rponte.penitent.PlsqlPackage} and {@link io.github.rponte.penitent.PlsqlSchema} declare
 * the container of the routines; {@link io.github.rponte.penitent.PlsqlBlock} runs an anonymous block.
 * <li>{@link io.github.rponte.penitent.PlsqlRoutine}, {@link io.github.rponte.penitent.PlsqlParam},
 * {@link io.github.rponte.penitent.PlsqlType}, {@link io.github.rponte.penitent.PlsqlAttr} and
 * {@link io.github.rponte.penitent.PlsqlBoolean} cover what the naming conventions and the dictionary do
 * not say.
 * <li>{@link io.github.rponte.penitent.PlsqlOut} holds an {@code OUT} or {@code IN OUT} parameter.
 * <li>{@link io.github.rponte.penitent.PlsqlSettings} gathers what applies to every interface: the
 * converters of {@link io.github.rponte.penitent.convert}, the default {@link io.github.rponte.penitent.PlsqlBind}
 * and the translator of {@link io.github.rponte.penitent.exception}.
 * </ul>
 *
 * <p>The packages under {@code io.github.rponte.penitent.internal} are not part of the API, and may change
 * in any version.
 */
package io.github.rponte.penitent;
