package io.github.rponte.penitent.mock;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Scripts applied in the test's edition when it begins, before {@code @BeforeEach}, as
 * {@link PlsqlMocks#applyScript(String)} and {@link PlsqlMocks#applyScriptText(String)} apply them: to replace a view,
 * a package, its body, a function, a procedure or a synonym that only the test sees.
 *
 * <pre>{@code
 * @Test
 * @PlsqlMockScript("/mocks/freight-items-v.sql")
 * void counts_the_items() {
 *     // ...
 * }
 * }</pre>
 *
 * <p>On the test class, it applies to each of its tests, before the method's own. It may be repeated, and each one
 * applies in its order: its {@link #scripts} first, then its {@link #scriptText}. It works with
 * {@link PlsqlMockExtension} and with {@code @EnablePlsqlMocks}, and is refused in a Spring test without the latter.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Repeatable(PlsqlMockScripts.class)
public @interface PlsqlMockScript {

	/** An alias of {@link #scripts}. */
	String[] value() default {};

	/**
	 * The locations of the scripts: on the classpath, as {@code /mocks/freight-items-v.sql} or with
	 * {@code classpath:}, or on the file system, with {@code file:}. Never SQL: that goes in {@link #scriptText}.
	 */
	String[] scripts() default {};

	/** A script given as text, in the same format as a file, applied after {@link #scripts}. */
	String scriptText() default "";
}
