package io.github.rponte.penitent;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * Binds a Java type to an Oracle composite type: an OBJECT, a package RECORD ({@code PACKAGE.RECORD})
 * or a table's {@code TABLE%ROWTYPE}. On a {@link PlsqlBlock} parameter, it may also name a collection.
 *
 * <p>Attributes match by <b>name</b>, not by position, and that is a requirement, not a preference:
 * the order of the Java fields often differs from the order of the type's attributes, and matching by
 * position would silently swap values between attributes of the same type.
 *
 * <p>It works for a {@code record} (components) and for a JavaBean (getters/setters). The convention
 * is camelCase→SNAKE_CASE; whatever deviates from it gets {@link PlsqlAttr}.
 *
 * <p>For a routine it is optional: the dictionary already says which type each parameter is, and the
 * annotation only has to agree with it. For a {@link PlsqlBlock}, which has no signature in the
 * dictionary, it is how a parameter says its Oracle type, and then it may also go on the
 * <b>parameter</b>, which is the only place for a collection:
 *
 * <pre>{@code
 * @PlsqlBlock("declare l NAME_LIST_T := :names; begin :result := l.count; end;")
 * int count(@PlsqlType("NAME_LIST_T") List<String> names);
 * }</pre>
 *
 * <p>A composite parameter may take it from its class instead. Without either, a composite or
 * collection parameter of a block is a {@link PlsqlMappingException}, before the call.
 */
@Retention(RUNTIME)
@Target({ TYPE, PARAMETER })
public @interface PlsqlType {

	/**
	 * Name of the type in Oracle, without the schema: {@code TYPE}, {@code PACKAGE.RECORD} or
	 * {@code TABLE%ROWTYPE}, an OBJECT, RECORD or collection. As in SQL, an unquoted name is read in
	 * upper case, and one in double quotes keeps its case.
	 */
	String value();
}
