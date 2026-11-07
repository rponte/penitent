package io.github.rponte.penitent;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.metadata.CompositeType;

/**
 * An anonymous PL/SQL block, for whatever does not have a {@code package.routine} shape.
 *
 * <p>The typical case is an object type <i>member method</i>: {@code Period_t.is_valid()} does not
 * show up in {@code ALL_ARGUMENTS} as a callable routine, and can only be invoked on an instance,
 * inside a block.
 *
 * <p>Here the library's rule is deliberately inverted: <b>a block has no signature in
 * the dictionary</b>, so there is nothing to validate and nothing to derive. Whatever the dictionary
 * would say, the annotation has to say, hence {@link #resultType()}. For a routine, the database is
 * the source of truth; for a block, the author is.
 *
 * <p>Parameters go in by <b>name</b>, with {@code :name} matching the Java parameter name, and the
 * return value comes out through {@code :result}:
 *
 * <pre>{@code
 * @PlsqlBlock("""
 *         declare
 *           p Period_t;
 *         begin
 *           p := new Period_t(:startDate, :endDate);
 *           :result := p.is_valid();
 *         end;
 *         """)
 * boolean isValid(LocalDate startDate, LocalDate endDate);
 * }</pre>
 *
 * <p>A Java {@code boolean}, as a parameter or in {@code :result}, is a PL/SQL {@code BOOLEAN}.
 * For a literal, annotate the parameter with {@link PlsqlBoolean} or, for {@code :result}, the
 * method.
 *
 * <p>A composite or collection parameter declares its Oracle type with {@link PlsqlType}, on the
 * parameter or, for a composite, on its class.
 *
 * <p>PL/SQL's {@code :=} is not mistaken for a placeholder, because a placeholder requires a letter
 * or {@code _} after the colon. Neither is a {@code :} inside a string literal (including
 * {@code q'[...]'}), a comment or a quoted identifier.
 */
@Retention(RUNTIME)
@Target(METHOD)
public @interface PlsqlBlock {

	/** The block, with {@code :name} for the parameters and {@code :result} for the return value. */
	String value();

	/**
	 * The Oracle name of the type of {@code :result}, when it is a composite type or a collection: the
	 * block has no dictionary entry to take it from. It follows the table in {@link CompositeType}:
	 * {@code TYPE}, {@code PACKAGE.RECORD} or {@code TABLE%ROWTYPE}, resolved in the session's schema,
	 * and the dictionary says whether it is a collection, which becomes a {@code List<T>}.
	 *
	 * <p>{@code SYS_REFCURSOR} makes {@code :result} a cursor, read as for a routine. A block that
	 * opens it, with {@code OPEN :result FOR}, does not need to say so. A {@code List} without either
	 * is a {@link PlsqlMappingException}: nothing would tell which collection it is.
	 */
	String resultType() default "";
}
