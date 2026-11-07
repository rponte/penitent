package io.github.rponte.penitent;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Which literals represent a Java boolean in Oracle. It applies to an attribute or column, to a
 * routine or block parameter and, on a method of a package interface, to the return value.
 *
 * <p>Without the annotation, the type in the dictionary decides: {@code VARCHAR2} uses
 * {@code Y}/{@code N} and {@code NUMBER} uses {@code 1}/{@code 0}. On a dictionary {@code BOOLEAN}
 * the annotation has no effect, because there are no literals to choose.
 *
 * <p>When reading, a value that is neither of the two literals is an error. When writing there is no
 * way to detect it: without the annotation, a database that expects {@code S} receives {@code Y}.
 */
@Retention(RUNTIME)
@Target({ FIELD, METHOD, PARAMETER, RECORD_COMPONENT })
public @interface PlsqlBoolean {

	String trueValue() default "Y";

	String falseValue() default "N";
}
