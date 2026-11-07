package io.github.rponte.penitent.convert;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The converter for this point: a parameter, an attribute or column and, on a method of a package
 * interface, the return value. It wins over {@link PlsqlConverters}. The class needs a no-argument
 * constructor.
 */
@Retention(RUNTIME)
@Target({ FIELD, METHOD, PARAMETER, RECORD_COMPONENT })
public @interface PlsqlConvert {

	Class<? extends PlsqlConverter<?, ?>> value();
}
