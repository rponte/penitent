package io.github.rponte.penitent.internal.convert;

import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverters;

/**
 * How the internals find the converter for a point without the API offering the lookup:
 * {@link PlsqlConverters} installs its own here when its class is initialized.
 */
public final class ConverterLookup {

	/** What {@link PlsqlConverters} lends to the internals. */
	public interface Access {

		PlsqlConverters none();

		AppliedConverter find(PlsqlConverters converters, PlsqlConvert annotation, Class<?> declared,
				Class<?> element, String where);
	}

	private static volatile Access access;

	private ConverterLookup() {
	}

	/** Called once, by {@link PlsqlConverters}. */
	public static void install(Access installed) {
		if (access != null) {
			throw new IllegalStateException("the converter lookup is already installed");
		}
		access = installed;
	}

	/** No converter at all, shared by every interface created without them. */
	public static PlsqlConverters none() {
		return access().none();
	}

	/**
	 * The converter for a value declared as {@code declared}, or {@code null} if there is none.
	 *
	 * @param element the {@code T} of a {@code List<T>} or {@code T[]}, or {@code null}
	 * @param where how to refer to this point in an error message
	 */
	public static AppliedConverter find(PlsqlConverters converters, PlsqlConvert annotation, Class<?> declared,
			Class<?> element, String where) {
		return access().find(converters, annotation, declared, element, where);
	}

	/** {@link #none()} may come before anything has initialized {@link PlsqlConverters}. */
	private static Access access() {
		if (access == null) {
			try {
				Class.forName(PlsqlConverters.class.getName(), true, PlsqlConverters.class.getClassLoader());
			} catch (ClassNotFoundException e) {
				throw new IllegalStateException(e);
			}
		}
		return access;
	}
}
