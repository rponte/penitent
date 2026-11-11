package io.github.rponte.penitent.mock.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * What the current thread is in the middle of, as in Mockito: the matchers given for the call being made, the last
 * call made on a mock, for {@code when}, and a verification waiting for its call.
 */
public final class Recording {

	private static final ThreadLocal<List<Matcher>> MATCHERS = ThreadLocal.withInitial(ArrayList::new);
	private static final ThreadLocal<Invocation> LAST = new ThreadLocal<>();
	private static final ThreadLocal<Verification> VERIFICATION = new ThreadLocal<>();

	/** A verification of {@code mock}, waiting for the call to verify. */
	public record Verification(Object mock, int times) {
	}

	private Recording() {
	}

	public static void matcher(Matcher matcher) {
		MATCHERS.get().add(matcher);
	}

	/** The matchers given since the last call, taken: the next call starts afresh. */
	static List<Matcher> takeMatchers() {
		List<Matcher> matchers = List.copyOf(MATCHERS.get());
		MATCHERS.get().clear();
		return matchers;
	}

	static void last(Invocation invocation) {
		LAST.set(invocation);
	}

	/** The last call made on a mock, taken; {@code null} when there was none. */
	public static Invocation takeLast() {
		Invocation invocation = LAST.get();
		LAST.remove();
		return invocation;
	}

	public static void verification(Verification verification) {
		VERIFICATION.set(verification);
	}

	/** The verification waiting for a call of {@code mock}, taken; {@code null} when there is none. */
	static Verification takeVerification(Object mock) {
		Verification verification = VERIFICATION.get();
		if (verification == null || verification.mock() != mock) {
			return null;
		}
		VERIFICATION.remove();
		return verification;
	}

	/** Forgets everything half done, after a misuse, so the next call starts clean. */
	public static void reset() {
		MATCHERS.get().clear();
		LAST.remove();
		VERIFICATION.remove();
	}
}
