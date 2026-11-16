package io.github.rponte.penitent.mock.internal;

import java.util.Arrays;
import java.util.List;

import io.github.rponte.penitent.mock.PlsqlMockScript;
import io.github.rponte.penitent.mock.PlsqlMocks;

/** Applies the {@link PlsqlMockScript}s a test framework found, in their order, refusing one that cannot mean anything. */
public final class AnnotatedScripts {

	private AnnotatedScripts() {
	}

	/**
	 * Applies {@code annotations}, found on {@code where}, such as {@code FreightTest.counts_the_items}: each one's
	 * scripts, then its text.
	 */
	public static void apply(PlsqlMocks mocks, List<PlsqlMockScript> annotations, String where) {
		for (PlsqlMockScript annotation : annotations) {
			String[] scripts = scriptsOf(annotation, where);
			if (scripts.length == 0 && annotation.scriptText().isBlank()) {
				throw new IllegalStateException("@PlsqlMockScript on " + where + " names no script: give it scripts, or"
						+ " value, with the locations of script files, or scriptText, with a script as text");
			}
			for (String location : scripts) {
				mocks.applyScript(location);
			}
			if (!annotation.scriptText().isBlank()) {
				mocks.applyScriptText(annotation.scriptText());
			}
		}
	}

	private static String[] scriptsOf(PlsqlMockScript annotation, String where) {
		String[] value = annotation.value();
		String[] scripts = annotation.scripts();
		if (value.length > 0 && scripts.length > 0 && !Arrays.equals(value, scripts)) {
			throw new IllegalStateException("@PlsqlMockScript on " + where + " has both value " + Arrays.toString(value)
					+ " and scripts " + Arrays.toString(scripts) + ", which are the same attribute: keep one of them");
		}
		return value.length > 0 ? value : scripts;
	}
}
