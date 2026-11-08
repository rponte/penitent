package io.github.rponte.penitent.integration;

/** Helpers for the tests that run the documentation's examples. */
final class Docs {

	private Docs() {
	}

	/**
	 * A message as the documentation quotes it: in the schema {@code SHOP} and, for a nested test
	 * interface, in the package {@code com.example.shop}.
	 */
	static String asDocumented(String message, Class<?> holder) {
		return message
				.replace("PLSQL_TEST.", "SHOP.")
				.replace(holder.getName() + "$", "com.example.shop.");
	}
}
