package io.github.rponte.penitent.internal.proxy;

import java.util.function.BiConsumer;

import io.github.rponte.penitent.PlsqlOut;

/**
 * How the proxy writes the value of a {@link PlsqlOut} without the API offering a setter: the class
 * installs its writer here when it is initialized, which always happens before there is an instance to
 * write to.
 */
public final class PlsqlOutAccess {

	private static volatile BiConsumer<PlsqlOut<?>, Object> writer;

	private PlsqlOutAccess() {
	}

	/** Called once, by {@link PlsqlOut}. */
	public static void install(BiConsumer<PlsqlOut<?>, Object> installed) {
		if (writer != null) {
			throw new IllegalStateException("the writer of PlsqlOut is already installed");
		}
		writer = installed;
	}

	static void set(PlsqlOut<?> out, Object value) {
		writer.accept(out, value);
	}
}
