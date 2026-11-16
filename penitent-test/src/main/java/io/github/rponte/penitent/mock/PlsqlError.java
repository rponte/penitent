package io.github.rponte.penitent.mock;

/**
 * An exception a stub raises, with {@link PlsqlMocks#doRaise} or {@code thenRaise}: one of Oracle's predefined
 * exceptions, a {@link PredefinedError}, or one a package declares in its spec, from {@link #declared}.
 */
public sealed interface PlsqlError permits PredefinedError, DeclaredError {

	/**
	 * An exception {@code packageName} declares in its spec, as in {@code no_quote EXCEPTION;}: raised as
	 * {@code RAISE package.exception}, so the PL/SQL that calls the stubbed routine catches it by name. The package
	 * may be in another schema, as {@code SCHEMA.PACKAGE}. Whether it exists is known only when the stub compiles.
	 */
	static PlsqlError declared(String packageName, String exceptionName) {
		return new DeclaredError(packageName, exceptionName);
	}

	/** The name {@code RAISE} takes. */
	String raiseName();
}
