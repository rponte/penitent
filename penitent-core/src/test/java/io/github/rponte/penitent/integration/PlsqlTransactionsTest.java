package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.PlsqlTransaction;
import io.github.rponte.penitent.PlsqlTransactions;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * The contract of {@link PlsqlTransactions}: a minimal, explicit transaction for the core alone. It
 * commits when the block returns, rolls back when it throws anything, keeps the original failure,
 * always gives the connection back as it found it, and refuses every way of leaving the transaction
 * without noticing: a proxy of the same {@code DataSource} called without {@code tx.using}, a view
 * or the connection used after the block or from another thread, a nested block, and a commit or
 * rollback on the connection it hands out.
 */
class PlsqlTransactionsTest extends OracleIntegrationTest {

	@PlsqlPackage("TX_PKG")
	interface Events {
		void recordEvent(String label);

		void recordAndFail(String label);

		void recordAndCommit(String label);

		int countEvents(String label);

		int sessionId();
	}

	static class OwnFailure extends RuntimeException {

		private static final long serialVersionUID = 1L;

		final PlsqlFailure failure;

		OwnFailure(PlsqlFailure failure) {
			super(failure.cause());
			this.failure = failure;
		}
	}

	static class FatalError extends Error {

		private static final long serialVersionUID = 1L;
	}

	private static String label(TestInfo test) {
		return test.getTestMethod().orElseThrow().getName() + "-" + System.nanoTime();
	}

	private static int committed(String label) throws SQLException {
		try (Connection other = DATA_SOURCE.getConnection()) {
			try (PreparedStatement query = other.prepareStatement("select count(*) from tx_event where label = ?")) {
				query.setString(1, label);
				try (ResultSet rows = query.executeQuery()) {
					rows.next();
					return rows.getInt(1);
				}
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

	private static int active(HikariDataSource pool) {
		return pool.getHikariPoolMXBean().getActiveConnections();
	}

	@Nested
	@DisplayName("the cycle of a block")
	class Cycle {

		@Test
		@DisplayName("a block that returns commits the calls and the JDBC work together")
		void commits(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);

			PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				tx.using(events).recordEvent(label);
				insert(tx.connection(), label);
				assertThat(committed(label)).as("nothing is visible before the commit").isZero();
			});

			assertThat(committed(label)).isEqualTo(2);
		}

		@Test
		@DisplayName("runWithResult returns the block's value, after the commit")
		void returns_a_value(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);

			int count = PlsqlTransactions.of(DATA_SOURCE).runWithResult(tx -> {
				Events inTransaction = tx.using(events);
				inTransaction.recordEvent(label);
				return inTransaction.countEvents(label);
			});

			assertThat(count).isEqualTo(1);
			assertThat(committed(label)).isEqualTo(1);
		}

		@Test
		@DisplayName("a RuntimeException rolls everything back, and comes out as it is")
		void runtime_exception(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);
			IllegalArgumentException failure = new IllegalArgumentException("the block failed");

			assertThatThrownBy(() -> PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				tx.using(events).recordEvent(label);
				insert(tx.connection(), label);
				throw failure;
			})).isSameAs(failure);

			assertThat(committed(label)).isZero();
		}

		@Test
		@DisplayName("a routine error rolls everything back, the calls before it included")
		void routine_error(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);

			assertThatThrownBy(() -> PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				Events inTransaction = tx.using(events);
				inTransaction.recordEvent(label);
				inTransaction.recordAndFail(label);
			})).isInstanceOf(PlsqlRoutineRaisedException.class);

			assertThat(committed(label)).isZero();
		}

		@Test
		@DisplayName("a checked exception rolls back too, and comes out without a wrapper")
		void checked_exception(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);
			IOException failure = new IOException("the block failed");

			assertThatThrownBy(() -> PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				tx.using(events).recordEvent(label);
				throw failure;
			})).isSameAs(failure);

			assertThat(committed(label)).isZero();
		}

		@Test
		@DisplayName("an Error rolls back too, and comes out as it is")
		void error(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);
			FatalError failure = new FatalError();

			assertThatThrownBy(() -> PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				tx.using(events).recordEvent(label);
				throw failure;
			})).isSameAs(failure);

			assertThat(committed(label)).isZero();
		}

		@Test
		@DisplayName("setRollbackOnly rolls back a block that returns, and the block still returns its value")
		void rollback_only(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);

			String result = PlsqlTransactions.of(DATA_SOURCE).runWithResult(tx -> {
				tx.using(events).recordEvent(label);
				assertThat(tx.isRollbackOnly()).isFalse();
				tx.setRollbackOnly();
				assertThat(tx.isRollbackOnly()).isTrue();
				return "returned";
			});

			assertThat(result).isEqualTo("returned");
			assertThat(committed(label)).isZero();
		}

		@Test
		@DisplayName("every call of the block runs in one session, the JDBC work's")
		void one_session() throws SQLException {
			Events events = pkg(Events.class);

			PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				int session = sessionOf(tx.connection());
				assertThat(tx.using(events).sessionId()).isEqualTo(session);
				assertThat(tx.using(events).sessionId()).isEqualTo(session);
			});
		}
	}

	@Nested
	@DisplayName("the connection, before and after")
	class TheConnection {

		@Test
		@DisplayName("the connection goes back to the pool, in auto-commit as it was, whatever the block did")
		void given_back_as_it_was(TestInfo test) throws SQLException {
			String label = label(test);
			try (HikariDataSource pool = newPool()) {
				pool.setMaximumPoolSize(1);
				Events events = PlsqlPackages.create(Events.class, pool);
				PlsqlTransactions transactions = PlsqlTransactions.of(pool);

				transactions.run(tx -> tx.using(events).recordEvent(label));
				assertThat(active(pool)).as("after a commit").isZero();

				assertThatThrownBy(() -> transactions.run(tx -> {
					throw new IllegalStateException("fails");
				})).isInstanceOf(IllegalStateException.class);
				assertThat(active(pool)).as("after a rollback").isZero();

				transactions.run(PlsqlTransaction::setRollbackOnly);
				assertThat(active(pool)).as("after a rollback-only").isZero();

				try (Connection connection = pool.getConnection()) {
					assertThat(connection.getAutoCommit()).as("auto-commit restored").isTrue();
				}
			}
		}

		@Test
		@DisplayName("the block restores auto-commit itself, for a DataSource that, unlike a pool, does not reset it")
		void restores_auto_commit_itself() throws SQLException {
			try (Connection connection = DATA_SOURCE.getConnection()) {
				DataSource withoutReset = sameConnectionKeptOpen(connection);

				PlsqlTransactions.of(withoutReset).run(tx -> assertThat(connection.getAutoCommit()).isFalse());
				assertThat(connection.getAutoCommit()).as("after a commit").isTrue();

				assertThatThrownBy(() -> PlsqlTransactions.of(withoutReset).run(tx -> {
					throw new IllegalStateException("fails");
				})).isInstanceOf(IllegalStateException.class);
				assertThat(connection.getAutoCommit()).as("after a rollback").isTrue();

				connection.setAutoCommit(false);
				PlsqlTransactions.of(withoutReset).run(tx -> {
				});
				assertThat(connection.getAutoCommit()).as("off stays off").isFalse();
				connection.setAutoCommit(true);
			}
		}

		@Test
		@DisplayName("a pool that hands out connections without auto-commit gets them back that way")
		void auto_commit_off_stays_off() throws SQLException {
			try (HikariDataSource pool = newPool()) {
				pool.setAutoCommit(false);
				pool.setMaximumPoolSize(1);

				PlsqlTransactions.of(pool).run(tx -> {
				});

				try (Connection connection = pool.getConnection()) {
					assertThat(connection.getAutoCommit()).isFalse();
				}
			}
		}

		@Test
		@DisplayName("a commit that fails rolls back, and arrives translated, with the TRANSACTION phase")
		void commit_fails(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);

			assertThatThrownBy(() -> PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				tx.using(events).recordEvent(label);
				try (PreparedStatement insert = tx.connection().prepareStatement("insert into tx_deferred values (-1)")) {
					insert.executeUpdate();
				}
			}))
					.isInstanceOfSatisfying(PlsqlExecutionException.class, e -> assertThat(e.getCode()).isEqualTo(2091))
					.isNotInstanceOf(PlsqlRoutineRaisedException.class);

			assertThat(committed(label)).isZero();
		}

		@Test
		@DisplayName("a failed commit goes through the settings' translator")
		void commit_failure_translated() {
			PlsqlTransactions transactions = PlsqlTransactions.of(DATA_SOURCE,
					PlsqlSettings.defaults().withExceptionTranslator(OwnFailure::new));

			assertThatThrownBy(() -> transactions.run(tx -> {
				try (PreparedStatement insert = tx.connection().prepareStatement("insert into tx_deferred values (-1)")) {
					insert.executeUpdate();
				}
			})).isInstanceOfSatisfying(OwnFailure.class,
					e -> assertThat(e.failure.phase()).isEqualTo(PlsqlFailure.Phase.TRANSACTION));
		}

		@Test
		@DisplayName("when the connection cannot be obtained, the block does not run, and the failure is translated")
		void no_connection() {
			DataSource broken = failing(Set.of("getConnection"));
			List<String> ran = new ArrayList<>();

			assertThatThrownBy(() -> PlsqlTransactions.of(broken).run(tx -> ran.add("ran")))
					.isInstanceOf(PlsqlExecutionException.class);
			assertThat(ran).isEmpty();
		}

		@Test
		@DisplayName("a rollback or close that fails is suppressed into the block's failure, never replaces it")
		void cleanup_failures_are_suppressed() {
			DataSource broken = failing(Set.of("rollback", "close"));
			IllegalArgumentException failure = new IllegalArgumentException("the block failed");

			assertThatThrownBy(() -> PlsqlTransactions.of(broken).run(tx -> {
				throw failure;
			})).isSameAs(failure).satisfies(e -> assertThat(e.getSuppressed()).hasSize(2));
		}
	}

	@Nested
	@DisplayName("the connection the block hands out")
	class ProtectedConnection {

		@Test
		@DisplayName("it refuses to commit, roll back, change auto-commit, close or abort")
		void refuses_to_end_the_transaction() throws SQLException {
			PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				Connection connection = tx.connection();

				assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class)
						.hasMessageContaining("PlsqlTransaction");
				assertThatThrownBy(connection::rollback).isInstanceOf(SQLException.class);
				assertThatThrownBy(() -> connection.setAutoCommit(true)).isInstanceOf(SQLException.class);
				assertThatThrownBy(connection::close).isInstanceOf(SQLException.class);
				assertThatThrownBy(() -> connection.abort(Runnable::run)).isInstanceOf(SQLException.class);
				assertThat(connection.isClosed()).isFalse();
			});
		}

		@Test
		@DisplayName("it allows savepoints, which do not end the transaction")
		void allows_savepoints(TestInfo test) throws SQLException {
			String label = label(test);

			PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				insert(tx.connection(), label);
				Savepoint savepoint = tx.connection().setSavepoint();
				insert(tx.connection(), label);
				tx.connection().rollback(savepoint);
			});

			assertThat(committed(label)).isEqualTo(1);
		}

		@Test
		@DisplayName("it unwraps to the driver's connection, for legacy code that needs it")
		void unwraps() throws SQLException {
			PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				assertThat(tx.connection().isWrapperFor(oracle.jdbc.OracleConnection.class)).isTrue();
				assertThat(tx.connection().unwrap(oracle.jdbc.OracleConnection.class)).isNotNull();
			});
		}
	}

	@Nested
	@DisplayName("what cannot leave the block")
	class Escapes {

		@Test
		@DisplayName("a view used after the block fails, instead of running outside the transaction")
		void view_after_the_block() {
			Events events = pkg(Events.class);
			AtomicReference<Events> escaped = new AtomicReference<>();

			PlsqlTransactions.of(DATA_SOURCE).run(tx -> escaped.set(tx.using(events)));

			assertThatThrownBy(() -> escaped.get().countEvents("escaped"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("ended");
		}

		@Test
		@DisplayName("the connection used after the block fails")
		void connection_after_the_block() {
			AtomicReference<Connection> escaped = new AtomicReference<>();

			PlsqlTransactions.of(DATA_SOURCE).run(tx -> escaped.set(tx.connection()));

			assertThatThrownBy(() -> escaped.get().prepareStatement("select 1 from dual"))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("ended");
		}

		@Test
		@DisplayName("a view or the connection used from another thread fails")
		void another_thread() {
			Events events = pkg(Events.class);

			PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				Events inTransaction = tx.using(events);
				Connection connection = tx.connection();

				assertThatThrownBy(() -> CompletableFuture.runAsync(() -> inTransaction.countEvents("thread")).get())
						.isInstanceOf(ExecutionException.class)
						.cause().isInstanceOf(IllegalStateException.class).hasMessageContaining("thread");
				assertThatThrownBy(() -> CompletableFuture.runAsync(() -> {
					try {
						connection.prepareStatement("select 1 from dual");
					} catch (SQLException e) {
						throw new IllegalStateException(e);
					}
				}).get()).isInstanceOf(ExecutionException.class).rootCause().hasMessageContaining("thread");
			});
		}

		@Test
		@DisplayName("a proxy of the same DataSource, called without tx.using, fails instead of leaving the transaction")
		void same_data_source_without_using(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);

			assertThatThrownBy(() -> PlsqlTransactions.of(DATA_SOURCE).run(tx -> events.recordEvent(label)))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("tx.using");

			assertThat(committed(label)).isZero();
			events.recordEvent(label);
			assertThat(committed(label)).as("outside the block it works as usual").isEqualTo(1);
		}

		@Test
		@DisplayName("a proxy of another DataSource is another resource: it runs on its own, outside the transaction")
		void another_data_source(TestInfo test) throws SQLException {
			String label = label(test);
			try (HikariDataSource other = newPool()) {
				Events elsewhere = PlsqlPackages.create(Events.class, other);

				PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
					elsewhere.recordEvent(label);
					tx.setRollbackOnly();
				});

				assertThat(committed(label)).isEqualTo(1);
			}
		}

		@Test
		@DisplayName("the same DataSource means the same object: an interface of a wrapper of it is not refused, "
				+ "and runs outside the transaction")
		void wrapped_data_source_is_not_recognized(TestInfo test) throws SQLException {
			String label = label(test);
			DataSource wrapped = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
					new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
						try {
							return method.invoke(DATA_SOURCE, args);
						} catch (InvocationTargetException e) {
							throw e.getCause();
						}
					});
			Events throughWrapper = PlsqlPackages.create(Events.class, wrapped);

			PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				throughWrapper.recordEvent(label);
				tx.setRollbackOnly();
			});

			assertThat(committed(label)).as("committed on its own, outside the block's rolled back transaction")
					.isEqualTo(1);
		}

		@Test
		@DisplayName("a block inside another, on the same thread, is refused, and the outer one goes on")
		void nested_block(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);
			PlsqlTransactions transactions = PlsqlTransactions.of(DATA_SOURCE);

			transactions.run(outer -> {
				outer.using(events).recordEvent(label);
				assertThatThrownBy(() -> PlsqlTransactions.of(DATA_SOURCE).run(inner -> {
				})).isInstanceOf(IllegalStateException.class).hasMessageContaining("already");
				outer.using(events).recordEvent(label);
			});

			assertThat(committed(label)).isEqualTo(2);
		}

		@Test
		@DisplayName("after a block, even one that failed, the thread is free for the next one")
		void thread_is_freed(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);
			PlsqlTransactions transactions = PlsqlTransactions.of(DATA_SOURCE);

			assertThatThrownBy(() -> transactions.run(tx -> {
				throw new IllegalStateException("fails");
			})).isInstanceOf(IllegalStateException.class);

			events.recordEvent(label);
			transactions.run(tx -> tx.using(events).recordEvent(label));

			assertThat(committed(label)).isEqualTo(2);
		}
	}

	@Nested
	@DisplayName("what the block does not protect against")
	class Limits {

		@Test
		@DisplayName("a routine's own COMMIT commits the block's work so far: a later failure no longer undoes it")
		void routine_commits(TestInfo test) throws SQLException {
			String label = label(test);
			Events events = pkg(Events.class);

			assertThatThrownBy(() -> PlsqlTransactions.of(DATA_SOURCE).run(tx -> {
				insert(tx.connection(), label);
				tx.using(events).recordAndCommit(label);
				throw new IllegalStateException("fails after the routine committed");
			})).isInstanceOf(IllegalStateException.class);

			assertThat(committed(label)).isEqualTo(2);
		}
	}

	/** A {@code DataSource} that hands out {@code connection} and ignores its close, without resetting anything. */
	private static DataSource sameConnectionKeptOpen(Connection connection) {
		Connection kept = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (p, m, a) -> {
					if (m.getName().equals("close")) {
						return null;
					}
					try {
						return m.invoke(connection, a);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] { DataSource.class },
				(proxy, method, args) -> {
					if (method.getName().equals("getConnection")) {
						return kept;
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}

	/**
	 * A {@code DataSource} over the test database whose connections fail the named methods; for
	 * {@code getConnection}, the {@code DataSource} itself fails.
	 */
	private static DataSource failing(Set<String> methods) {
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] { DataSource.class },
				(proxy, method, args) -> {
					if (method.getName().equals("getConnection")) {
						if (methods.contains("getConnection")) {
							throw new SQLException("no connection, on purpose");
						}
						Connection real = DATA_SOURCE.getConnection();
						return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
								(p, m, a) -> {
									if (methods.contains(m.getName())) {
										if (m.getName().equals("close")) {
											real.close();
										}
										throw new SQLException(m.getName() + " fails, on purpose");
									}
									try {
										return m.invoke(real, a);
									} catch (InvocationTargetException e) {
										throw e.getCause();
									}
								});
					}
					try {
						return method.invoke(DATA_SOURCE, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}
}
