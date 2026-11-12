package io.github.rponte.penitent.mock.internal;

import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The log of the mocks, under {@code io.github.rponte.penitent.mock}, in the format of the log of the calls: the
 * edition of each test, each stub and each verify at DEBUG; the PL/SQL generated for a stub at TRACE, since it can
 * be long; and only what is out of the ordinary at WARN. Nothing at INFO, so tests stay quiet by default. The
 * values shown are a test's, never production data.
 */
final class MockLog {

	static final String MOCK = "io.github.rponte.penitent.mock";

	private static final Logger LOG = LoggerFactory.getLogger(MOCK);

	/** The width of the labels, as in the log of the calls; a continuation line aligns with the value. */
	private static final int LABEL = 19;
	private static final String CONTINUATION = " ".repeat(LABEL + 3);

	private MockLog() {
	}

	static void began(String edition, long start) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("{}: edition {} in {} ms", label("Began mocks"), edition, elapsed(start));
		}
	}

	static void ended(String edition, long start) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("{}: edition {} dropped in {} ms", label("Ended mocks"), edition, elapsed(start));
		}
	}

	static void leftover(String edition) {
		LOG.warn("{}: {}, left by a run that died before ending its mocks", label("Dropped edition"), edition);
	}

	static void stubbed(String call, Stub.Action action, long start) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("{}: {} -> {} in {} ms", label("Stubbed routine"), call, describe(action), elapsed(start));
		}
	}

	static void generated(String code) {
		if (LOG.isTraceEnabled()) {
			LOG.trace("{}: {}", label("-> generated PL/SQL"), code.strip().replace("\n", "\n" + CONTINUATION));
		}
	}

	static void verified(String call, int times) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("{}: {} called {}, as expected", label("Verified routine"), call,
					times == 1 ? "1 time" : times + " times");
		}
	}

	/** {@code SCHEMA.PACKAGE.ROUTINE('SP', any())}: the routine and what each argument has to be. */
	static String call(String routine, Iterable<Matcher> matchers) {
		StringJoiner arguments = new StringJoiner(", ", routine + "(", ")");
		matchers.forEach(matcher -> arguments.add(matcher.toString()));
		return arguments.toString();
	}

	private static String describe(Stub.Action action) {
		if (action instanceof Stub.Return returned) {
			return returned.function() ? "RETURN " + PlsqlLiterals.of(returned.value()) : "RETURN";
		}
		if (action instanceof Stub.ApplicationError error) {
			return "RAISE_APPLICATION_ERROR(" + error.code() + ", " + PlsqlLiterals.of(error.message()) + ")";
		}
		return "RAISE " + ((Stub.Predefined) action).name();
	}

	private static String label(String label) {
		return String.format(java.util.Locale.ROOT, "%-" + LABEL + "s ", label);
	}

	private static long elapsed(long start) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}
}
