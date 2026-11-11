package io.github.rponte.penitent.mock;

import java.lang.reflect.Proxy;
import java.util.Objects;

import javax.sql.DataSource;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.mock.internal.EditionDataSource;
import io.github.rponte.penitent.mock.internal.Invocation;
import io.github.rponte.penitent.mock.internal.Matcher;
import io.github.rponte.penitent.mock.internal.MockHandler;
import io.github.rponte.penitent.mock.internal.MockSession;
import io.github.rponte.penitent.mock.internal.Recording;
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
 * lasts. Architecture decision 0001 has the design.
 *
 * <p>The schema needs the scripts {@code penitent-mock-admin.sql} and {@code penitent-mock-schema.sql}, shipped
 * in this module, applied once: {@link #begin} checks them, and {@link #prepare} applies them.
 */
public final class PlsqlMocks implements AutoCloseable {

	private final MockSession session;

	private PlsqlMocks(MockSession session) {
		this.session = session;
	}

	/** {@code dataSource}, handing out connections in the edition of the test running, if any. */
	public static DataSource wrap(DataSource dataSource) {
		Objects.requireNonNull(dataSource, "dataSource");
		return dataSource instanceof EditionDataSource ? dataSource : new EditionDataSource(dataSource);
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
		Recording.reset();
		session.end();
	}

	/** {@link #end}, when the test began. */
	@Override
	public void close() {
		end();
	}

	/** A mock of {@code type}, an interface of Penitent: its calls are only recorded, for {@link #when} and {@link #verify}. */
	public <T> T mock(Class<T> type) {
		Objects.requireNonNull(type, "type");
		return session.mock(type);
	}

	/** Stubs the call just made on a mock, which returns a value. */
	public static <T> Stubbing<T> when(T call) {
		return new Stubbing<>(lastInvocation(), true);
	}

	/**
	 * Stubs a call that returns nothing, made inside {@code call}: a procedure. With {@link #out} arguments, it sets
	 * them and returns; {@link Stubbing#thenThrow} raises instead.
	 */
	public static Stubbing<Void> when(Runnable call) {
		Recording.takeLast();
		call.run();
		Stubbing<Void> stubbing = new Stubbing<>(lastInvocation(), false);
		stubbing.then(new Stub.Return(null, false));
		return stubbing;
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
		if (mock == null || !Proxy.isProxyClass(mock.getClass())
				|| !(Proxy.getInvocationHandler(mock) instanceof MockHandler)) {
			throw new IllegalArgumentException("verify(...) needs a mock, from PlsqlMocks.mock");
		}
		Recording.verification(new Recording.Verification(mock, times.count()));
		return mock;
	}

	public static Times times(int count) {
		return new Times(count);
	}

	public static Times never() {
		return new Times(0);
	}

	/** Matches any value, {@code null} included. */
	public static <T> T any() {
		Recording.matcher(new Matcher.Any());
		return null;
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
		private final boolean function;
		private Stub current;

		private Stubbing(Invocation invocation, boolean function) {
			this.invocation = invocation;
			this.function = function;
		}

		/** Returns {@code value}. */
		public void thenReturn(T value) {
			if (!function) {
				throw new IllegalStateException("a procedure returns nothing: its OUT values go in out(...) arguments");
			}
			then(new Stub.Return(value, true));
		}

		/** Raises an application error, as {@code RAISE_APPLICATION_ERROR(code, message)} would, code between -20999 and -20000. */
		public void thenThrow(int code, String message) {
			if (code < -20999 || code > -20000) {
				throw new IllegalArgumentException("an application error has a code between -20999 and -20000, not " + code);
			}
			then(new Stub.ApplicationError(code, Objects.requireNonNull(message, "message")));
		}

		/** Raises one of Oracle's predefined exceptions, by name, such as {@code NO_DATA_FOUND}. */
		public void thenRaise(String predefinedException) {
			if (predefinedException == null || !predefinedException.matches("[A-Za-z][A-Za-z0-9_$#]*")) {
				throw new IllegalArgumentException("not the name of an exception: " + predefinedException);
			}
			then(new Stub.Predefined(predefinedException.toUpperCase(java.util.Locale.ROOT)));
		}

		private void then(Stub.Action action) {
			Stub stub = new Stub(invocation, action);
			invocation.mock().session().stub(stub, current);
			current = stub;
		}
	}
}
