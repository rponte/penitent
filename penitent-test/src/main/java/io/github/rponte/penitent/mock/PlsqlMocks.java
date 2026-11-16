package io.github.rponte.penitent.mock;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import javax.sql.DataSource;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.mock.internal.EditionDataSource;
import io.github.rponte.penitent.mock.internal.Invocation;
import io.github.rponte.penitent.mock.internal.Matcher;
import io.github.rponte.penitent.mock.internal.MockHandler;
import io.github.rponte.penitent.mock.internal.MockSession;
import io.github.rponte.penitent.mock.internal.Recording;
import io.github.rponte.penitent.mock.internal.ScriptSources;
import io.github.rponte.penitent.mock.internal.Scripts;
import io.github.rponte.penitent.mock.internal.Stub;

/**
 * Mocks routines of PL/SQL packages in integration tests, in the style of Mockito, from the same interfaces the
 * application calls them through.
 *
 * <pre>{@code
 * DataSource dataSource = PlsqlMocks.wrap(realDataSource);   // what the application uses in tests
 * try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
 *     mocks.begin();
 *     Freights freights = mocks.mock(Freights.class);
 *     when(freights.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));
 *     // ... the code under test, against the real database ...
 *     verify(freights).calculate("SP", "CE");
 * }
 * }</pre>
 *
 * <p>Each test gets an edition of its own, and a stubbed routine is rewritten in it, in the package body the
 * database has: every other routine of the package stays real, and other PL/SQL that calls the routine sees the
 * mock too. Connections handed out by the {@code DataSource} of {@link #wrap} run in the test's edition while it
 * lasts. A stub answers with scalars, object types and collections. {@link #applyScript} replaces a whole view or
 * package in the edition too, for what a stub cannot reach. Architecture decision 0001 has the design.
 *
 * <p>The schema needs the scripts {@code penitent-mock-admin.sql} and {@code penitent-mock-schema.sql}, shipped
 * in this module, applied once: {@link #begin} checks them, and {@link #prepare} applies them.
 */
public final class PlsqlMocks implements AutoCloseable {

	/** The wrapped DataSource of each pool; both weak, so that a pool no longer used goes away with its wrapper. */
	private static final Map<DataSource, WeakReference<EditionDataSource>> WRAPPED = new WeakHashMap<>();

	private final MockSession session;

	private PlsqlMocks(MockSession session) {
		this.session = session;
	}

	/**
	 * {@code dataSource}, handing out connections in the edition of the test running, if any. The application and
	 * {@link #on} have to use the same wrapped object, since the edition is kept in it: so the same pool always
	 * gives the same wrapped {@code DataSource}, however many times it is wrapped, and a wrapped one is returned as it
	 * is.
	 */
	public static DataSource wrap(DataSource dataSource) {
		Objects.requireNonNull(dataSource, "dataSource");
		if (dataSource instanceof EditionDataSource) {
			return dataSource;
		}
		synchronized (WRAPPED) {
			WeakReference<EditionDataSource> known = WRAPPED.get(dataSource);
			EditionDataSource wrapped = known == null ? null : known.get();
			if (wrapped == null) {
				wrapped = new EditionDataSource(dataSource);
				WRAPPED.put(dataSource, new WeakReference<>(wrapped));
			}
			return wrapped;
		}
	}

	/** Mocks for the database of {@code dataSource}, which must come from {@link #wrap}. */
	public static PlsqlMocks on(DataSource dataSource) {
		if (!(dataSource instanceof EditionDataSource wrapped)) {
			throw new IllegalArgumentException("the DataSource has to come from PlsqlMocks.wrap: otherwise its"
					+ " connections never enter the test's edition, and the code under test never sees the mocks");
		}
		return new PlsqlMocks(new MockSession(wrapped));
	}

	/**
	 * Applies the two prerequisite scripts for {@code schema}: the admin one through {@code admin}, which needs
	 * DBA privileges, and the schema one as the schema itself, through {@code owner}. Meant for a disposable test
	 * database, since {@code ENABLE EDITIONS} cannot be undone.
	 */
	public static void prepare(DataSource admin, DataSource owner, String schema) {
		Scripts.apply(Scripts.ADMIN, admin, schema);
		Scripts.apply(Scripts.SCHEMA, owner, schema);
	}

	/** Starts a test: checks the prerequisites and creates the test's edition. */
	public void begin() {
		session.begin();
	}

	/** Ends the test: connections leave the edition, which is dropped, and so are the calls it recorded. */
	public void end() {
		Invocation unused = Recording.takeLast();
		IllegalStateException pending = null;
		try {
			Recording.refusePending();
		} catch (IllegalStateException e) {
			pending = e;
		}
		Recording.reset();
		session.end();
		if (unused != null) {
			throw Recording.unused(unused);
		}
		if (pending != null) {
			throw pending;
		}
	}

	/** {@link #end}, when the test began. */
	@Override
	public void close() {
		end();
	}

	/**
	 * Applies the script at {@code location} in the test's edition: a view, a package, its body, a function, a
	 * procedure or a synonym replaced with {@code CREATE OR REPLACE}, which only the test sees, and which go with the
	 * edition. The location is on the classpath, as {@code /mocks/freight-items-v.sql} or with {@code classpath:}, or
	 * on the file system, with {@code file:}; SQL is given to {@link #applyScriptText}. PL/SQL ends on a line holding
	 * only {@code /}, and SQL with {@code ;} or such a line. Every statement is checked before any runs, and the
	 * script runs on a connection of the mocks' own, so it commits nothing of the test's transaction.
	 */
	public void applyScript(String location) {
		session.applyScript(ScriptSources.read(location), location);
	}

	/** {@link #applyScript(String)}, for a script on the file system. */
	public void applyScript(Path file) {
		Objects.requireNonNull(file, "file");
		session.applyScript(ScriptSources.read(file), file.toString());
	}

	/** {@link #applyScript(String)}, for a script read from {@code script}, as UTF-8, which is left open. */
	public void applyScript(InputStream script) {
		Objects.requireNonNull(script, "script");
		session.applyScript(ScriptSources.read(script), "the script's stream");
	}

	/** {@link #applyScript(String)}, for a script given as text, in the same format as a file. */
	public void applyScriptText(String script) {
		Objects.requireNonNull(script, "script");
		session.applyScript(script, "the script text");
	}

	/** A mock of {@code type}, an interface of Penitent: its calls are only recorded, for {@link #when} and {@link #verify}. */
	public <T> T mock(Class<T> type) {
		Objects.requireNonNull(type, "type");
		return session.mock(type);
	}

	/** Stubs the call just made on a mock, which returns a value. */
	public static <T> Stubbing<T> when(T call) {
		Recording.refusePending();
		return new Stubbing<>(lastInvocation());
	}

	/**
	 * Stubs the next call made on the mock to do nothing: for a procedure, which returns nothing, as in
	 * {@code doNothing().when(freights).quote(eq("CE"), out(value))}, setting its {@link #out} arguments.
	 */
	public static Stubber doNothing() {
		return new Stubber(new Stub.Return(null, false), "doNothing()");
	}

	/** Stubs the next call made on the mock to return {@code value}: for a function, as {@code when(...).thenReturn} does. */
	public static Stubber doReturn(Object value) {
		return new Stubber(new Stub.Return(value, true), "doReturn(" + value + ")");
	}

	/**
	 * Stubs the next call made on the mock to raise an application error, as {@code RAISE_APPLICATION_ERROR(code,
	 * message)} would, code between -20999 and -20000: for a procedure or a function.
	 */
	public static Stubber doThrow(int code, String message) {
		return new Stubber(Stubbing.applicationError(code, message), "doThrow(" + code + ", ...)");
	}

	/** Stubs the next call made on the mock to raise one of Oracle's predefined exceptions, by name, such as {@code NO_DATA_FOUND}. */
	public static Stubber doRaise(String predefinedException) {
		return new Stubber(Stubbing.predefined(predefinedException), "doRaise(" + predefinedException + ")");
	}

	private static Invocation lastInvocation() {
		Invocation invocation = Recording.takeLast();
		if (invocation == null) {
			Recording.reset();
			throw new IllegalStateException("when(...) needs a call made on a mock, from PlsqlMocks.mock, inside it");
		}
		return invocation;
	}

	/** Checks that the next call made on {@code mock} was made once, in this test. */
	public static <T> T verify(T mock) {
		return verify(mock, times(1));
	}

	/** Checks that the next call made on {@code mock} was made as many times as {@code times} says, in this test. */
	public static <T> T verify(T mock, Times times) {
		Objects.requireNonNull(times, "times");
		requireMock(mock, "verify(...)");
		Recording.refusePending();
		Recording.verification(new Recording.Verification(mock, times.count()));
		return mock;
	}

	public static Times times(int count) {
		return new Times(count);
	}

	public static Times never() {
		return new Times(0);
	}

	/**
	 * Matches any value, {@code null} included. Not for a parameter of a primitive type: Java unboxes the
	 * {@code null} it returns before the call reaches the mock; {@link #anyInt()} and the like are for those.
	 */
	public static <T> T any() {
		Recording.matcher(new Matcher.Any());
		return null;
	}

	/** {@link #any()}, for an {@code int} parameter: returns 0, which Java does not unbox. */
	public static int anyInt() {
		Recording.matcher(new Matcher.Any());
		return 0;
	}

	/** {@link #any()}, for a {@code long} parameter. */
	public static long anyLong() {
		Recording.matcher(new Matcher.Any());
		return 0L;
	}

	/** {@link #any()}, for a {@code short} parameter. */
	public static short anyShort() {
		Recording.matcher(new Matcher.Any());
		return 0;
	}

	/** {@link #any()}, for a {@code byte} parameter. */
	public static byte anyByte() {
		Recording.matcher(new Matcher.Any());
		return 0;
	}

	/** {@link #any()}, for a {@code double} parameter. */
	public static double anyDouble() {
		Recording.matcher(new Matcher.Any());
		return 0d;
	}

	/** {@link #any()}, for a {@code float} parameter. */
	public static float anyFloat() {
		Recording.matcher(new Matcher.Any());
		return 0f;
	}

	/** {@link #any()}, for a {@code boolean} parameter: returns {@code false}. */
	public static boolean anyBoolean() {
		Recording.matcher(new Matcher.Any());
		return false;
	}

	/** Matches {@code value}; needed beside other matchers, since a call uses matchers for all of its arguments or none. */
	public static <T> T eq(T value) {
		Recording.matcher(new Matcher.Equal(value));
		return value;
	}

	/** Matches {@code null}. */
	public static <T> T isNull() {
		Recording.matcher(new Matcher.IsNull());
		return null;
	}

	/** In place of a {@link PlsqlOut} argument: the value the stubbed routine sets it to. */
	public static <T> PlsqlOut<T> out(T value) {
		Recording.matcher(new Matcher.Out(value));
		return null;
	}

	private static void requireMock(Object mock, String what) {
		if (mock == null || !Proxy.isProxyClass(mock.getClass())
				|| !(Proxy.getInvocationHandler(mock) instanceof MockHandler)) {
			throw new IllegalArgumentException(what + " needs a mock, from PlsqlMocks.mock");
		}
	}

	/**
	 * The answer of a {@code doXxx()}, waiting for the call it stubs, as in Mockito: the answer comes first, so the stub
	 * is compiled once, when the call arrives.
	 */
	public static final class Stubber {

		private final Stub.Action action;
		private final String label;

		private Stubber(Stub.Action action, String label) {
			this.action = action;
			this.label = label;
		}

		/** {@code mock}, whose next call is stubbed with this answer, as in {@code doNothing().when(mock).routine(...)}. */
		public <T> T when(T mock) {
			requireMock(mock, label + ".when(...)");
			Recording.pending(new Recording.Pending(mock, action, label));
			return mock;
		}
	}

	/** How many times a call is expected, for {@link #verify(Object, Times)}. */
	public record Times(int count) {
		public Times {
			if (count < 0) {
				throw new IllegalArgumentException("a call cannot be expected " + count + " times");
			}
		}
	}

	/**
	 * What a stubbed call does. Each {@code then...} compiles the stub into the test's edition at once: from then
	 * on, the routine answers as stubbed, to Java and to PL/SQL alike. When several stubs match a call, the last
	 * one wins, as in Mockito: a general stub first, the specific ones after it.
	 */
	public static final class Stubbing<T> {

		private final Invocation invocation;
		private Stub current;

		private Stubbing(Invocation invocation) {
			this.invocation = invocation;
		}

		/** Returns {@code value}. */
		public void thenReturn(T value) {
			then(new Stub.Return(value, true));
		}

		/** Raises an application error, as {@code RAISE_APPLICATION_ERROR(code, message)} would, code between -20999 and -20000. */
		public void thenThrow(int code, String message) {
			then(applicationError(code, message));
		}

		/** Raises one of Oracle's predefined exceptions, by name, such as {@code NO_DATA_FOUND}. */
		public void thenRaise(String predefinedException) {
			then(predefined(predefinedException));
		}

		static Stub.Action applicationError(int code, String message) {
			if (code < -20999 || code > -20000) {
				throw new IllegalArgumentException("an application error has a code between -20999 and -20000, not " + code);
			}
			return new Stub.ApplicationError(code, Objects.requireNonNull(message, "message"));
		}

		static Stub.Action predefined(String predefinedException) {
			if (predefinedException == null || !predefinedException.matches("[A-Za-z][A-Za-z0-9_$#]*")) {
				throw new IllegalArgumentException("not the name of an exception: " + predefinedException);
			}
			return new Stub.Predefined(predefinedException.toUpperCase(java.util.Locale.ROOT));
		}

		private void then(Stub.Action action) {
			current = invocation.mock().session().stub(new Stub(invocation, action), current);
		}
	}
}
