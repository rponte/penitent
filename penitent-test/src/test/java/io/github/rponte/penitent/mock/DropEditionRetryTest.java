package io.github.rponte.penitent.mock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Dropping the test's edition may hit an error the database expects to be run past: a deadlock, ORA-00060, which
 * Oracle 19c on the Autonomous Database showed in the CI, as did ORA-00604 over ORA-04043. The errors are injected
 * here, on the first drops, since they could not be provoked on purpose.
 */
class DropEditionRetryTest {

	/** How many more DROP EDITION statements fail, and with which error code. */
	private final AtomicInteger failures = new AtomicInteger();
	private volatile int errorCode = 60;

	private ListAppender<ILoggingEvent> logged;

	@BeforeEach
	void listen() {
		logged = new ListAppender<>();
		logged.start();
		((Logger) LoggerFactory.getLogger("io.github.rponte.penitent.mock")).addAppender(logged);
	}

	@AfterEach
	void stopListening() {
		((Logger) LoggerFactory.getLogger("io.github.rponte.penitent.mock")).detachAppender(logged);
	}

	/**
	 * The test pool, whose DROP EDITION statements fail while {@link #failures} lasts. It answers {@code equals} and
	 * {@code hashCode} by itself: PlsqlMocks.wrap keeps a wrapper per DataSource, and would otherwise hand back the
	 * pool's own.
	 */
	private DataSource failingDrops() {
		DataSource pool = TestDatabase.pool();
		return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { DataSource.class },
				(proxy, method, args) -> switch (method.getName()) {
					case "equals" -> proxy == args[0];
					case "hashCode" -> System.identityHashCode(proxy);
					case "toString" -> "the test pool, with failing drops";
					default -> {
						Object result = invoke(method, pool, args);
						yield result instanceof Connection connection ? failing(connection) : result;
					}
				});
	}

	private Connection failing(Connection connection) {
		return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Connection.class },
				(proxy, method, args) -> {
					Object result = invoke(method, connection, args);
					// only a plain statement runs a DROP EDITION: a prepared one keeps its own type
					return method.getName().equals("createStatement") ? failing((Statement) result) : result;
				});
	}

	private Statement failing(Statement statement) {
		return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Statement.class },
				(proxy, method, args) -> {
					if (method.getName().equals("execute") && args != null && args[0] instanceof String sql
							&& sql.startsWith("DROP EDITION") && failures.getAndDecrement() > 0) {
						throw new SQLException("ORA-" + String.format("%05d", errorCode) + ": injected", "61000",
								errorCode);
					}
					return invoke(method, statement, args);
				});
	}

	private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	@Test
	@DisplayName("a deadlock on the first drop is run past: the drop is tried again, logged, and the edition goes")
	void a_deadlock_is_run_past() {
		PlsqlMocks mocks = PlsqlMocks.on(PlsqlMocks.wrap(failingDrops()));
		mocks.begin();
		failures.set(1);

		mocks.end();

		assertThat(TestKit.editionsLeft(TestDatabase.pool())).isZero();
		assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
				.anySatisfy(line -> assertThat(line).contains("Retried drop").contains("ORA-00060"));
	}

	@Test
	@DisplayName("ORA-00604 over ORA-04043 on the first drop is run past too")
	void a_vanished_object_is_run_past() {
		PlsqlMocks mocks = PlsqlMocks.on(PlsqlMocks.wrap(failingDrops()));
		mocks.begin();
		errorCode = 604;
		failures.set(1);

		mocks.end();

		assertThat(TestKit.editionsLeft(TestDatabase.pool())).isZero();
	}

	@Test
	@DisplayName("a deadlock on every attempt fails end(), saying so, and the next begin() drops what is left")
	void a_deadlock_on_every_attempt_fails() {
		DataSource dataSource = PlsqlMocks.wrap(failingDrops());
		PlsqlMocks mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		failures.set(100);

		assertThatThrownBy(mocks::end)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ORA-00060")
				.hasMessageContaining("3 attempts");
		failures.set(0);
		PlsqlMocks next = PlsqlMocks.on(dataSource);
		next.begin();
		next.end();
		assertThat(TestKit.editionsLeft(TestDatabase.pool())).isZero();
	}

	@Test
	@DisplayName("any other error of the drop fails end() at once, with no retry")
	void another_error_is_not_retried() {
		PlsqlMocks mocks = PlsqlMocks.on(PlsqlMocks.wrap(failingDrops()));
		mocks.begin();
		errorCode = 38805;
		failures.set(1);

		assertThatThrownBy(mocks::end)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("ORA-38805");
		assertThat(logged.list).extracting(ILoggingEvent::getFormattedMessage)
				.noneMatch(line -> line.contains("Retried drop"));
		PlsqlMocks next = PlsqlMocks.on(PlsqlMocks.wrap(TestDatabase.pool()));
		next.begin();
		next.end();
	}
}
