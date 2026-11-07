package io.github.rponte.penitent;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Maps an interface to the routines that live directly in a schema, outside any package.
 *
 * <p>An Oracle function or procedure does not need a container other than the schema, but a Java
 * method does, so the interface plays that role, as it does for a package with {@link PlsqlPackage}.
 * An interface declares one container: {@link PlsqlPackage} or {@code @PlsqlSchema}, not both. Method
 * names, {@link PlsqlRoutine}, {@link PlsqlOut}, {@code DEFAULT} and synonyms work as in a package.
 *
 * <pre>{@code
 * @PlsqlSchema
 * public interface Utilities {
 *     // FUNCTION round_half_even(p_value NUMBER) RETURN NUMBER, outside any package
 *     BigDecimal roundHalfEven(BigDecimal value);
 * }
 * }</pre>
 */
@Retention(RUNTIME)
@Target(TYPE)
public @interface PlsqlSchema {

	/**
	 * The schema; empty uses the session's schema. As in SQL, an unquoted name is read in upper case,
	 * and one in double quotes keeps its case.
	 */
	String value() default "";

	/**
	 * How the parameters bind to the routine's: by position or by name. Not declared, it is
	 * {@link PlsqlSettings#bind()}, which is by position unless changed.
	 */
	PlsqlBind bind() default PlsqlBind.DEFAULT;
}
