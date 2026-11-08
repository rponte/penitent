package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * The contract of {@link PlsqlPackages#using}: a view of an interface bound to a connection of the
 * application's, so that calls take part in a transaction the application, or its framework,
 * controls. The view shares the interface's dictionary cache and settings, runs every call on that
 * connection, and never closes, commits or rolls it back, nor changes its auto-commit.
 */
class ConnectionBoundViewTest extends OracleIntegrationTest {

	record Event(Long id, String label) {
	}

	@PlsqlPackage("TX_PKG")
	interface Events {
		void recordEvent(String label);

		void recordAndFail(String label);

		int countEvents(String label);

		List<Event> events(String label);

		int sessionId();

		@PlsqlBlock("begin :result := sys_context('userenv', 'sid'); end;")
		int sessionIdByBlock();
	}

	@PlsqlPackage("TX_PKG")
	interface Broken {
		void doesNotExist();
	}

	static class OwnFailure extends RuntimeException {

		private static final long serialVersionUID = 1L;

		OwnFailure(Throwable cause) {
			super(cause);
		}
	}

	private static String label(TestInfo test) {
		return test.getTestMethod().orElseThrow().getName() + "-" + System.nanoTime();
	}

	private static int committed(String label) throws SQLException {
		try (Connection other = DATA_SOURCE.getConnection()) {
			return count(other, label);
		}
	}

	private static int count(Connection connection, String label) throws SQLException {
		try (PreparedStatement query = connection.prepareStatement("select count(*) from tx_event where label = ?")) {
			query.setString(1, label);
			try (ResultSet rows = query.executeQuery()) {
				rows.next();
				return rows.getInt(1);
			}
		}
	}

	private static void insert(Connection connection, String label) throws SQLException {
		try (PreparedStatement insert = connection.prepareStatement("insert into tx_event (label) values (?)")) {
			insert.setString(1, label);
			insert.executeUpdate();
		}
	}

	private static int sessionOf(Connection connection) throws SQLException {
		try (PreparedStatement query = connection.prepareStatement("select sys_context('userenv', 'sid') from dual");
				ResultSet rows = query.executeQuery()) {
			rows.next();
			return rows.getInt(1);
		}
	}

	private final Events events = pkg(Events.class);

	@Test
	@DisplayName("1. every call of the view runs on the connection it was given, routines and blocks alike")
	void runs_on_the_connection() throws SQLException {
		try (Connection connection = DATA_SOURCE.getConnection()) {
			Events view = PlsqlPackages.using(events, connection);

			assertThat(view.sessionId()).isEqualTo(sessionOf(connection));
			assertThat(view.sessionIdByBlock()).isEqualTo(sessionOf(connection));
		}
	}

	@Test
	@DisplayName("1. calls of the view and plain JDBC share the application's transaction")
	void shares_the_transaction(TestInfo test) throws SQLException {
		String label = label(test);
		try (Connection connection = DATA_SOURCE.getConnection()) {
			connection.setAutoCommit(false);
			try {
				Events view = PlsqlPackages.using(events, connection);

				view.recordEvent(label);
				insert(connection, label);
				assertThat(view.countEvents(label)).isEqualTo(2);
				assertThat(view.events(label)).hasSize(2);
				assertThat(committed(label)).isZero();

				connection.rollback();
				assertThat(view.countEvents(label)).isZero();

				view.recordEvent(label);
				connection.commit();
				assertThat(committed(label)).isEqualTo(1);
			} finally {
				connection.rollback();
				connection.setAutoCommit(true);
			}
		}
	}

	@Test
	@DisplayName("2. the view never closes, commits or rolls back the connection, nor changes its auto-commit")
	void leaves_the_connection_alone(TestInfo test) throws SQLException {
		String label = label(test);
		try (Connection connection = DATA_SOURCE.getConnection()) {
			connection.setAutoCommit(false);
			try {
				Events view = PlsqlPackages.using(events, connection);

				view.recordEvent(label);
				view.countEvents(label);
				assertThatThrownBy(() -> view.recordAndFail(label)).isInstanceOf(PlsqlRoutineRaisedException.class);
				assertThatThrownBy(() -> PlsqlPackages.using(PlsqlPackages.create(Broken.class, DATA_SOURCE), connection)
						.doesNotExist()).isInstanceOf(PlsqlMappingException.class);

				assertThat(connection.isClosed()).isFalse();
				assertThat(connection.getAutoCommit()).isFalse();
				assertThat(view.countEvents(label)).as("nothing was rolled back").isEqualTo(1);
				assertThat(committed(label)).as("nothing was committed").isZero();
			} finally {
				connection.rollback();
				connection.setAutoCommit(true);
			}
		}
	}

	@Test
	@DisplayName("3. the view uses what the interface already read: no dictionary query, neither to create it nor to call it")
	void shares_the_cache() throws SQLException {
		QueryCounter counter = new QueryCounter(DATA_SOURCE);
		Events original = PlsqlPackages.create(Events.class, counter.dataSource());
		original.countEvents("warm-up");

		try (Connection connection = counter.dataSource().getConnection()) {
			counter.reset();
			Events view = PlsqlPackages.using(original, connection);
			assertThat(counter.dictionaryQueries()).as("to create the view").isZero();

			view.countEvents("warm-up");
			assertThat(counter.dictionaryQueries()).as("to call a method the interface already read").isZero();
		}
	}

	@Test
	@DisplayName("4. what the view reads, on its connection, is cached for the interface too, and the other way around")
	void cache_goes_both_ways() throws SQLException {
		QueryCounter counter = new QueryCounter(DATA_SOURCE);
		Events original = PlsqlPackages.create(Events.class, counter.dataSource());

		try (Connection connection = counter.dataSource().getConnection()) {
			Events view = PlsqlPackages.using(original, connection);

			view.countEvents("first-read-by-the-view");
			counter.reset();
			original.countEvents("then-by-the-interface");
			assertThat(counter.dictionaryQueries()).as("read by the view, used by the interface").isZero();

			original.sessionId();
			counter.reset();
			view.sessionId();
			assertThat(counter.dictionaryQueries()).as("read by the interface after the view was created").isZero();
		}
	}

	@Test
	@DisplayName("4. the dictionary is read on the view's connection")
	void reads_the_dictionary_on_its_connection() throws SQLException {
		AtomicInteger borrowed = new AtomicInteger();
		DataSource counting = countingConnections(borrowed);
		Events original = PlsqlPackages.create(Events.class, counting);

		try (Connection connection = DATA_SOURCE.getConnection()) {
			Events view = PlsqlPackages.using(original, connection);
			view.countEvents("never-read-before");

			assertThat(borrowed).as("the view took no connection from the interface's DataSource").hasValue(0);
		}
	}

	@Test
	@DisplayName("5. the view keeps the interface's settings: its exception translator")
	void keeps_the_settings(TestInfo test) throws SQLException {
		String label = label(test);
		Events translated = PlsqlPackages.create(Events.class, DATA_SOURCE,
				PlsqlSettings.defaults().withExceptionTranslator(failure -> new OwnFailure(failure.cause())));

		try (Connection connection = DATA_SOURCE.getConnection()) {
			Events view = PlsqlPackages.using(translated, connection);

			assertThatThrownBy(() -> view.recordAndFail(label)).isInstanceOf(OwnFailure.class);
		}
	}

	@Test
	@DisplayName("5. a view of a view is bound to the newer connection, with the same cache")
	void view_of_a_view() throws SQLException {
		try (Connection first = DATA_SOURCE.getConnection(); Connection second = DATA_SOURCE.getConnection()) {
			Events view = PlsqlPackages.using(PlsqlPackages.using(events, first), second);

			assertThat(view.sessionId()).isEqualTo(sessionOf(second));
		}
	}

	@Test
	@DisplayName("6. using rejects what it cannot bind, with a clear message")
	void rejects_what_it_cannot_bind() throws SQLException {
		try (Connection connection = DATA_SOURCE.getConnection()) {
			assertThatThrownBy(() -> PlsqlPackages.using(null, connection))
					.isInstanceOf(NullPointerException.class).hasMessage("proxy");
			assertThatThrownBy(() -> PlsqlPackages.using(events, null))
					.isInstanceOf(NullPointerException.class).hasMessage("connection");
			assertThatThrownBy(() -> PlsqlPackages.using("not a proxy", connection))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessage("java.lang.String is not a proxy created by PlsqlPackages");
		}
	}

	@Test
	@DisplayName("7. validate checks the view's interface on the view's connection")
	void validate_on_the_view() throws SQLException {
		AtomicInteger borrowed = new AtomicInteger();
		Broken broken = PlsqlPackages.create(Broken.class, countingConnections(borrowed));

		try (Connection connection = DATA_SOURCE.getConnection()) {
			Broken view = PlsqlPackages.using(broken, connection);

			assertThatThrownBy(() -> PlsqlPackages.validate(view)).isInstanceOf(PlsqlMappingException.class);
			PlsqlPackages.validate(PlsqlPackages.using(events, connection));
			assertThat(borrowed).hasValue(0);
			assertThat(connection.isClosed()).isFalse();
		}
	}

	@Test
	@DisplayName("8. a view whose connection was closed fails as a connection failure, translated")
	void closed_connection() throws SQLException {
		Connection connection = DATA_SOURCE.getConnection();
		Events view = PlsqlPackages.using(events, connection);
		connection.close();

		assertThatThrownBy(view::sessionId)
				.isInstanceOf(PlsqlExecutionException.class)
				.isNotInstanceOf(PlsqlRoutineRaisedException.class);
	}

	@Test
	@DisplayName("9. a view is a proxy of the same interface, distinct from the original")
	void identity() throws SQLException {
		try (Connection connection = DATA_SOURCE.getConnection()) {
			Events view = PlsqlPackages.using(events, connection);

			assertThat(view).isInstanceOf(Events.class).isNotSameAs(events).isNotEqualTo(events).isEqualTo(view);
			assertThat(view.toString()).isEqualTo(
					"PlsqlPackages proxy for " + Events.class.getName() + ", bound to a connection");
		}
	}

	@Test
	@DisplayName("10. a view nobody references is collected, while the interface lives on")
	void view_is_collected() throws SQLException, InterruptedException {
		WeakReference<Events> reference = useAViewOnce();

		for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
			System.gc();
			Thread.sleep(20);
		}

		assertThat(reference.get()).as("the view was collected").isNull();
		assertThat(events.countEvents("collected")).as("and the interface still works").isZero();
	}

	@Test
	@DisplayName("11. in a loop, one view per transaction, the dictionary is read once in all")
	void one_view_per_transaction() throws SQLException {
		QueryCounter counter = new QueryCounter(DATA_SOURCE);
		Events original = PlsqlPackages.create(Events.class, counter.dataSource());

		for (int i = 0; i < 200; i++) {
			try (Connection connection = counter.dataSource().getConnection()) {
				connection.setAutoCommit(false);
				PlsqlPackages.using(original, connection).countEvents("loop");
				connection.rollback();
				connection.setAutoCommit(true);
			}
		}

		counter.reset();
		try (Connection connection = counter.dataSource().getConnection()) {
			PlsqlPackages.using(original, connection).countEvents("loop");
		}
		assertThat(counter.dictionaryQueries()).isZero();
	}

	/**
	 * In a method of its own, so that no local variable of the test keeps the view reachable: the
	 * interpreter keeps a local's slot alive until its method ends.
	 */
	private WeakReference<Events> useAViewOnce() throws SQLException {
		try (Connection connection = DATA_SOURCE.getConnection()) {
			Events view = PlsqlPackages.using(events, connection);
			view.countEvents("collected");
			return new WeakReference<>(view);
		}
	}

	/** A {@code DataSource} that counts the connections taken from it. */
	private static DataSource countingConnections(AtomicInteger borrowed) {
		return (DataSource) java.lang.reflect.Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					if (method.getName().equals("getConnection")) {
						borrowed.incrementAndGet();
					}
					try {
						return method.invoke(DATA_SOURCE, args);
					} catch (java.lang.reflect.InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}
}
