package io.github.rponte.penitent;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The routine name, for when the convention does not apply.
 *
 * <p>By convention the camelCase method name becomes SNAKE_CASE:
 * {@code calculateFreight} looks up {@code CALCULATE_FREIGHT}.
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface PlsqlRoutine {

	String value();
}
