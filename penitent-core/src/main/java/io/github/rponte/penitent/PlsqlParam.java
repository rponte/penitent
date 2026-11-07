package io.github.rponte.penitent;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The name of a parameter: the {@code :name} of a {@link PlsqlBlock}, or the Oracle name of a
 * routine's parameter with {@link PlsqlBind#BY_NAME}, such as
 * {@code @PlsqlParam("P_RLIST_PRODUCT_TYPE")}.
 *
 * <p>It is for code not compiled with {@code -parameters}, or when the name in the database differs
 * from the Java name. On a routine with {@link PlsqlBind#BY_POSITION}, the library's default, it has
 * no effect: the parameters bind by position.
 */
@Retention(RUNTIME)
@Target(PARAMETER)
public @interface PlsqlParam {

	String value();
}
