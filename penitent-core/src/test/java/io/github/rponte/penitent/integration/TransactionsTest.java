package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * How calls use connections and transactions, without Spring. The library takes a connection from
 * the {@code DataSource} for each call and closes it at the end; it never commits, rolls back or
 * changes auto-commit. These tests pin down what follows from that, for calls alone and mixed with
 * plain JDBC on the same connection.
 */
class TransactionsTest extends OracleIntegrationTest {

	record Event(Long id, String label) {
	}

	@PlsqlPackage("TX_PKG")
	interface Events {
		void recordEvent(String label);

		void recordAndFail(String label);

		int countEvents(String label);

		void countOut(String label, PlsqlOut<Integer> count);

		List<Event> events(String label);

		int sessionId();

		void recordAndCommit(String label);

		void recordAndRollback(String label);

		void recordAutonomously(String label);

		List<Event> eventsAfterCommit(String label);
	}

	@PlsqlPackage("TX_PKG")
	interface Broken {
		void doesNotExist();

		String recordEvent(String label);
	}

	/** A label of its own for each test: what one test commits stays in the table. */
	private static String label(TestInfo test) {
		return test.getTestMethod().orElseThrow().getName() + "-" + System.nanoTime();
	}

	/** What another session sees: only what was committed. */
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

	@Nested
	@DisplayName("with a pool in auto-commit, as most applications have")
	class AutoCommit {

		private final Events events = pkg(Events.class);

		@Test
		@DisplayName("each call commits what it did, on its own")
		void each_call_commits(TestInfo test) throws SQLException {
			String label = label(test);

			events.recordEvent(label);

			assertThat(committed(label)).isEqualTo(1);
		}

		@Test
		@DisplayName("a call that fails undoes only its own work: the calls before it stay committed")
		void failure_keeps_previous_calls(TestInfo test) throws SQLException {
			String label = label(test);

			events.recordEvent(label);
			assertThatThrownBy(() -> events.recordAndFail(label)).isInstanceOf(PlsqlRoutineRaisedException.class);

			assertThat(committed(label)).isEqualTo(1);
		}

		@Test
		@DisplayName("every call gives its connection back, whether it succeeds, fails or never reaches the database")
		void connections_go_back_to_the_pool(TestInfo test) {
			String label = label(test);
			try (HikariDataSource pool = newPool()) {
				Events pooled = PlsqlPackages.create(Events.class, pool);
				Broken broken = PlsqlPackages.create(Broken.class, pool);

				pooled.recordEvent(label);
				assertThat(active(pool)).as("after a call").isZero();

				assertThatThrownBy(() -> pooled.recordAndFail(label)).isInstanceOf(PlsqlRoutineRaisedException.class);
				assertThat(active(pool)).as("after a routine error").isZero();

				assertThatThrownBy(broken::doesNotExist).isInstanceOf(PlsqlMappingException.class);
				assertThat(active(pool)).as("after a routine that does not exist").isZero();

				assertThatThrownBy(() -> broken.recordEvent(label)).isInstanceOf(PlsqlMappingException.class);
				assertThat(active(pool)).as("after a method that does not match").isZero();

				assertThat(pooled.events(label)).hasSize(1);
				assertThat(active(pool)).as("after reading a cursor").isZero();

				assertThatThrownBy(() -> PlsqlPackages.validate(broken)).isInstanceOf(PlsqlMappingException.class);
				assertThat(active(pool)).as("after a validation").isZero();
			}
		}

		private static int active(HikariDataSource pool) {
			return pool.getHikariPoolMXBean().getActiveConnections();
		}
	}

	@Nested
	@DisplayName("with a pool that is not in auto-commit, and nothing that commits")
	class PoolWithoutAutoCommit {

		@Test
		@DisplayName("the call succeeds, but its work is lost when the pool takes the connection back")
		void work_is_lost(TestInfo test) throws SQLException {
			String label = label(test);
			try (HikariDataSource pool = newPool()) {
				pool.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, pool);

				events.recordEvent(label);

				assertThat(committed(label)).isZero();
			}
		}
	}

	@Nested
	@DisplayName("with a connection of the application's, in a transaction it controls")
	class SharedConnection {

		@Test
		@DisplayName("calls and plain JDBC share the transaction: they see each other's work, and nobody else does")
		void calls_and_jdbc_share_the_transaction(TestInfo test) throws SQLException {
			String label = label(test);
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));
				try {
					events.recordEvent(label);
					insert(connection, label);

					assertThat(events.countEvents(label)).as("the call sees the JDBC insert").isEqualTo(2);
					assertThat(count(connection, label)).as("JDBC sees the call's insert").isEqualTo(2);
					assertThat(committed(label)).as("another session sees nothing").isZero();

					connection.rollback();
					assertThat(events.countEvents(label)).as("the rollback undoes both").isZero();

					events.recordEvent(label);
					insert(connection, label);
					connection.commit();
					assertThat(committed(label)).as("the commit keeps both").isEqualTo(2);
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("the library never commits nor changes auto-commit, and closes the connection after each call")
		void library_leaves_the_transaction_alone(TestInfo test) throws SQLException {
			String label = label(test);
			AtomicInteger closes = new AtomicInteger();
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, closes));
				try {
					events.recordEvent(label);
					events.recordEvent(label);
					events.countEvents(label);

					assertThat(connection.getAutoCommit()).isFalse();
					assertThat(committed(label)).isZero();
					assertThat(closes).as("one close per call").hasValue(3);
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("a call that fails undoes only its own work: the transaction goes on, and the application decides")
		void failure_in_the_middle(TestInfo test) throws SQLException {
			String label = label(test);
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));
				try {
					events.recordEvent(label);
					assertThatThrownBy(() -> events.recordAndFail(label))
							.isInstanceOf(PlsqlRoutineRaisedException.class);

					assertThat(events.countEvents(label)).as("the first call is still pending").isEqualTo(1);
					assertThat(committed(label)).isZero();

					connection.commit();
					assertThat(committed(label)).as("a commit after the failure keeps the first call").isEqualTo(1);
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("OUT parameters and cursors read what the transaction has not committed yet")
		void out_and_cursor_see_the_transaction(TestInfo test) throws SQLException {
			String label = label(test);
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));
				try {
					events.recordEvent(label);
					insert(connection, label);

					PlsqlOut<Integer> count = new PlsqlOut<>();
					events.countOut(label, count);
					assertThat(count.get()).isEqualTo(2);
					assertThat(events.events(label)).hasSize(2).allSatisfy(e -> assertThat(e.label()).isEqualTo(label));
					assertThat(committed(label)).isZero();
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("every call runs in the connection's session")
		void same_session(TestInfo test) throws SQLException {
			try (Connection connection = DATA_SOURCE.getConnection()) {
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));

				assertThat(events.sessionId()).isEqualTo(sessionOf(connection));
				assertThat(events.sessionId()).isEqualTo(sessionOf(connection));
			}
		}
	}

	@Nested
	@DisplayName("with a routine that commits or rolls back by itself, as much legacy PL/SQL does")
	class RoutineControlsTheTransaction {

		@Test
		@DisplayName("in auto-commit: a COMMIT changes nothing, and a ROLLBACK undoes the call's work without an error")
		void in_auto_commit(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);

			events.recordAndCommit(label);
			events.recordAndRollback(label);

			assertThat(committed(label)).isEqualTo(1);
		}

		@Test
		@DisplayName("a COMMIT inside the routine commits the whole transaction, the application's work before the call included")
		void commit_inside(TestInfo test) throws SQLException {
			String label = label(test);
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));
				try {
					insert(connection, label);
					events.recordAndCommit(label);

					assertThat(committed(label)).as("the JDBC insert was committed by the routine").isEqualTo(2);

					connection.rollback();
					assertThat(committed(label)).as("the application's rollback comes too late").isEqualTo(2);
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("a ROLLBACK inside the routine undoes the whole transaction, without an error, and the transaction goes on")
		void rollback_inside(TestInfo test) throws SQLException {
			String label = label(test);
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));
				try {
					insert(connection, label);
					events.recordEvent(label);
					events.recordAndRollback(label);

					assertThat(count(connection, label)).as("everything before it is gone").isZero();

					insert(connection, label);
					connection.commit();
					assertThat(committed(label)).as("only what came after it is committed").isEqualTo(1);
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("a ROLLBACK inside the routine also erases the application's savepoints")
		void rollback_inside_erases_savepoints(TestInfo test) throws SQLException {
			String label = label(test);
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));
				try {
					insert(connection, label);
					Savepoint savepoint = connection.setSavepoint();
					events.recordAndRollback(label);

					assertThatThrownBy(() -> connection.rollback(savepoint))
							.isInstanceOf(SQLException.class)
							.hasMessageContaining("ORA-01086");
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("an autonomous transaction commits on its own: the application's rollback does not undo it")
		void autonomous_transaction(TestInfo test) throws SQLException {
			String label = label(test);
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));
				try {
					insert(connection, label);
					events.recordAutonomously(label);

					assertThat(committed(label)).as("only the autonomous insert").isEqualTo(1);

					connection.rollback();
					assertThat(committed(label)).as("the rollback undoes only the application's insert").isEqualTo(1);
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("a cursor opened before the routine's COMMIT is still read whole")
		void cursor_across_commit(TestInfo test) throws SQLException {
			String label = label(test);
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));
				try {
					insert(connection, label);
					insert(connection, label);

					assertThat(events.eventsAfterCommit(label)).hasSize(2);
					assertThat(committed(label)).as("and the routine committed the inserts").isEqualTo(2);
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("committing or rolling back again, after the routine did, is not an error")
		void commit_and_rollback_again(TestInfo test) throws SQLException {
			String label = label(test);
			try (Connection connection = DATA_SOURCE.getConnection()) {
				connection.setAutoCommit(false);
				Events events = PlsqlPackages.create(Events.class, alwaysIn(connection, new AtomicInteger()));
				try {
					events.recordAndCommit(label);
					connection.commit();
					connection.commit();

					events.recordAndRollback(label);
					connection.rollback();
					connection.rollback();

					assertThat(committed(label)).isEqualTo(1);
				} finally {
					connection.rollback();
					connection.setAutoCommit(true);
				}
			}
		}

		@Test
		@DisplayName("in auto-commit, the driver refuses a commit or a rollback by the application")
		void commit_in_auto_commit() throws SQLException {
			try (Connection connection = DATA_SOURCE.getConnection()) {
				assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class).hasMessageContaining("ORA-17273");
				assertThatThrownBy(connection::rollback).isInstanceOf(SQLException.class).hasMessageContaining("ORA-17274");
			}
		}
	}

	/**
	 * A {@code DataSource} that always hands out the same connection, and counts the closes it
	 * ignores: the application, not the library, decides when the connection ends.
	 */
	private static DataSource alwaysIn(Connection connection, AtomicInteger closes) {
		Connection withoutClosing = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					if (method.getName().equals("close")) {
						closes.incrementAndGet();
						return null;
					}
					try {
						return method.invoke(connection, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					if (method.getName().equals("getConnection")) {
						return withoutClosing;
					}
					try {
						return method.invoke(DATA_SOURCE, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}
}
