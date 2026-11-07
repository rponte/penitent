package io.github.rponte.penitent;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Binds a Java interface to a PL/SQL package: one interface per package, and only on types. The package
 * is the container of the routines, and the interface plays that role on the Java side. For the
 * routines outside any package, whose container is the schema, use {@link PlsqlSchema}; an interface
 * declares one of the two, not both.
 *
 * <pre>{@code
 * @PlsqlPackage("FREIGHT_CALCULATOR")
 * public interface FreightCalculator {
 *     // FUNCTION calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER
 *     BigDecimal calculateFreight(String originState, String destinationState);
 * }
 * }</pre>
 *
 * <p>Two packages are two interfaces, and whoever needs both injects both.
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface PlsqlPackage {

	/**
	 * Package name, without the schema; it cannot be empty. As in SQL, an unquoted name is read in
	 * upper case, and one in double quotes keeps its case:
	 * {@code @PlsqlPackage("\"myPackage\"")} is the package created as {@code "myPackage"}.
	 */
	String value();

	/** Schema; empty uses the session's schema. Quoted or not, as {@link #value()}. */
	String schema() default "";

	/**
	 * How the parameters bind to the routine's: by position or by name. Not declared, it is
	 * {@link PlsqlSettings#bind()}, which is by position unless changed.
	 */
	PlsqlBind bind() default PlsqlBind.DEFAULT;
}
