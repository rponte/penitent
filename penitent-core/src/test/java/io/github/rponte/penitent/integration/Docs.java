package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.UnaryOperator;

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

	/**
	 * Fails unless each page block, written as {@code page#name}, is exactly what the library
	 * printed. The page is the expected value: when the library changes, the page changes with it.
	 */
	static void assertPagesShow(String actual, String... refs) {
		assertPagesShow(UnaryOperator.identity(), actual, refs);
	}

	/** The same, comparing both sides through {@code normalized}, for what changes between runs. */
	static void assertPagesShow(UnaryOperator<String> normalized, String actual, String... refs) {
		for (String ref : refs) {
			assertThat(normalized.apply(actual))
					.as("docs/%s must show what the library prints", ref)
					.isEqualTo(normalized.apply(Pages.block(ref)));
		}
	}
}
