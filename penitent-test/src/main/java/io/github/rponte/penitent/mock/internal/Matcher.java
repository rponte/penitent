package io.github.rponte.penitent.mock.internal;

/**
 * What an argument of a stubbed or verified call has to be, written as a PL/SQL condition on an expression: a
 * parameter, in a stub, or a recorded argument, in a verification.
 */
public sealed interface Matcher {

	/** The condition on {@code expression}, or {@code null} when any value matches. */
	String condition(String expression);

	/** A plain value, or {@code eq(value)}. */
	record Equal(Object value) implements Matcher {
		@Override
		public String condition(String expression) {
			return value == null ? expression + " IS NULL" : expression + " = " + PlsqlLiterals.of(value);
		}

		@Override
		public String toString() {
			return value == null ? "null" : PlsqlLiterals.of(value);
		}
	}

	/** {@code any()}. */
	record Any() implements Matcher {
		@Override
		public String condition(String expression) {
			return null;
		}

		@Override
		public String toString() {
			return "any()";
		}
	}

	/** {@code isNull()}. */
	record IsNull() implements Matcher {
		@Override
		public String condition(String expression) {
			return expression + " IS NULL";
		}

		@Override
		public String toString() {
			return "isNull()";
		}
	}

	/** {@code out(value)}: matches anything, since an OUT parameter has no value coming in, and sets it to {@code value}. */
	record Out(Object value) implements Matcher {
		@Override
		public String condition(String expression) {
			return null;
		}

		@Override
		public String toString() {
			return "out(" + PlsqlLiterals.of(value) + ")";
		}
	}
}
