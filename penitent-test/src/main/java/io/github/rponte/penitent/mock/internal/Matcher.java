package io.github.rponte.penitent.mock.internal;

import io.github.rponte.penitent.PlsqlOut;

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
			return value == null ? "null" : shown(value);
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
			return "out(" + shown(value) + ")";
		}
	}

	/**
	 * A {@link PlsqlOut} given as it is, or in {@code eq()}: the value it holds is what came in through the parameter.
	 * The session resolves it by the parameter's mode, before it is used (see {@code MockSession.resolved}): an
	 * {@code IN OUT} matches the value as {@link Equal} does, and a pure {@code OUT}, which nothing comes in through,
	 * takes no value.
	 */
	record CameIn(Object value) implements Matcher {
		@Override
		public String condition(String expression) {
			return new Equal(value).condition(expression);
		}

		@Override
		public String toString() {
			return new Equal(value).toString();
		}
	}

	/**
	 * {@code inOut(cameIn, goesOut)}, for an {@code IN OUT} parameter: matches {@code cameIn} coming in, as
	 * {@link Equal} does, and sets the parameter to {@code goesOut}.
	 */
	record InOut(Object cameIn, Object goesOut) implements Matcher {
		@Override
		public String condition(String expression) {
			return new Equal(cameIn).condition(expression);
		}

		@Override
		public String toString() {
			return "inOut(" + new Equal(cameIn) + ", " + shown(goesOut) + ")";
		}
	}

	/**
	 * A value as a log or a message shows it: as PL/SQL where the mocks write it so, and otherwise as Java has it, such
	 * as an enum a verification matches before the core encodes it.
	 */
	private static String shown(Object value) {
		try {
			return PlsqlLiterals.of(value);
		} catch (IllegalStateException e) {
			return String.valueOf(value);
		}
	}
}
