package io.github.rponte.penitent.mock;

import java.util.Locale;
import java.util.regex.Pattern;

/** An exception a package declares in its spec; see {@link PlsqlError#declared}. */
public record DeclaredError(String packageName, String exceptionName) implements PlsqlError {

	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*");
	private static final Pattern PACKAGE = Pattern.compile("([A-Za-z][A-Za-z0-9_$#]*\\.)?[A-Za-z][A-Za-z0-9_$#]*");

	public DeclaredError {
		if (packageName == null || !PACKAGE.matcher(packageName).matches()) {
			throw new IllegalArgumentException("not the name of a package, as PACKAGE or SCHEMA.PACKAGE: " + packageName);
		}
		if (exceptionName == null || !IDENTIFIER.matcher(exceptionName).matches()) {
			throw new IllegalArgumentException("not the name of an exception: " + exceptionName);
		}
		packageName = packageName.toUpperCase(Locale.ROOT);
		exceptionName = exceptionName.toUpperCase(Locale.ROOT);
	}

	@Override
	public String raiseName() {
		return packageName + "." + exceptionName;
	}
}
