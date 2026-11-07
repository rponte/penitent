package io.github.rponte.penitent.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import javax.sql.DataSource;

import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlSchema;

/**
 * Registers a bean for each interface the library can implement, found in the given packages: those
 * annotated with {@link PlsqlPackage} or {@link PlsqlSchema}, and those that declare a {@link PlsqlBlock}
 * method. Each bean is created by {@link SpringPlsqlPackages}, so its calls take part in the current
 * transaction.
 *
 * <p>With no package given, the package of the annotated class is scanned, as with
 * {@code @ComponentScan}.
 *
 * <p>An interface found here should not also be created by hand as a bean: there would be two beans of
 * the same type, and injecting it would be ambiguous.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(PlsqlPackagesRegistrar.class)
public @interface EnablePlsqlPackages {

	/** Alias for {@link #basePackages()}. */
	@AliasFor("basePackages")
	String[] value() default {};

	/** The packages to scan, including their subpackages. */
	@AliasFor("value")
	String[] basePackages() default {};

	/** A type-safe alternative to {@link #basePackages()}: the package of each class is scanned. */
	Class<?>[] basePackageClasses() default {};

	/**
	 * The name of the {@link DataSource} bean the interfaces use. Empty means the only {@code DataSource}
	 * in the context.
	 */
	String dataSource() default "";

	/**
	 * Whether interfaces nested in another type are registered too. Off by default, as in Spring Data's
	 * {@code considerNestedRepositories}: a nested interface is usually a test fixture, sometimes broken
	 * on purpose, that lives in the same package as the real ones.
	 */
	boolean considerNested() default false;

	/**
	 * Whether the interfaces are checked against the database when the context starts, with
	 * {@link io.github.rponte.penitent.PlsqlPackages#validate}: a mismatch stops the context, naming every
	 * interface and method that does not match, instead of failing on the first call. It also leaves
	 * the dictionary cached, so the first calls do not read it.
	 *
	 * <p>Off by default: with it on, the context does not start without the database.
	 */
	boolean validateOnStartup() default false;
}
