package io.github.rponte.penitent;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * The attribute name in Oracle, for when the camelCase→SNAKE_CASE convention does not apply.
 *
 * <p>It accepts <b>more than one name</b> because the same Java type shows up in the database under
 * different spellings: {@code OrderItem.description} is {@code DESCRIPTION} in the record
 * {@code ORDERS_PKG.ORDER_ITEM} and {@code ORDER_ITEM_DESCRIPTION} in the cursor column of
 * {@code FIND_ORDER_ITEMS}. Listing both is more honest than duplicating
 * the entity or loosening the matching.
 *
 * <p>No name may be claimed by two properties of the same class; if it is, the library fails saying
 * which ones, instead of picking one.
 */
@Retention(RUNTIME)
@Target({ FIELD, METHOD, RECORD_COMPONENT })
public @interface PlsqlAttr {

	String[] value();
}
