package io.github.rponte.penitent.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * What Spring users expect and the core does not know about: the ongoing transaction and the
 * exception hierarchy. Beyond that, it is the same mapping the {@code penitent-core} tests already prove.
 */
class SpringPlsqlPackagesTest {

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;
	private static TransactionTemplate transaction;

	record Address(String street, Integer num) {
	}

	@PlsqlPackage("FIXTURE_SPRING")
	interface Fixture {
		int sessionId();

		int countItems();

		void insertItem(int id);

		void raiseError();

		void sqlInvalid();

		void lockTable();

		@PlsqlRoutine("INSERT_ITEM")
		void typeTheDriverRefuses(java.net.URI id);

		Address echoAddress(Address value);

		List<String> echoNames(List<String> value);

		void insertAndCommit(int id);

		void insertAndRollback(int id);
	}

	@PlsqlPackage("FIXTURE_SPRING")
	interface NonexistentRoutine {
		void doesNotExist();
	}

	@PlsqlPackage("FIXTURE_SPRING_INVALID")
	interface Invalid {
		void anything();
	}

	@BeforeAll
	static void startDatabase() {
		dataSource = TestDatabase.dataSource();
		jdbc = new JdbcTemplate(dataSource);
		transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	private final Fixture fixture = SpringPlsqlPackages.create(Fixture.class, dataSource);

	private int currentSession() {
		return jdbc.queryForObject("select sys_context('userenv', 'sid') from dual", Integer.class);
	}

	@Test
	@DisplayName("inside a transaction, the call uses its session")
	void same_session_as_transaction() {
		transaction.executeWithoutResult(status -> {
			assertThat(fixture.sessionId()).isEqualTo(currentSession());
			assertThat(fixture.echoAddress(new Address("Street A", 1))).isEqualTo(new Address("Street A", 1));
			assertThat(fixture.echoNames(List.of("a"))).containsExactly("a");
			assertThat(fixture.sessionId()).isEqualTo(currentSession());
		});
	}

	@Test
	@DisplayName("the call sees what the transaction did, and the rollback undoes what the call did")
	void takes_part_in_rollback() {
		int before = fixture.countItems();

		transaction.executeWithoutResult(status -> {
			jdbc.update("insert into fixture_spring_item values (1)");
			assertThat(fixture.countItems()).isEqualTo(before + 1);

			fixture.insertItem(2);
			assertThat(fixture.countItems()).isEqualTo(before + 2);

			status.setRollbackOnly();
		});

		assertThat(fixture.countItems()).isEqualTo(before);
	}

	@Test
	@DisplayName("outside a transaction, each call commits what it did")
	void without_transaction() {
		int before = fixture.countItems();

		fixture.insertItem(100);

		assertThat(fixture.countItems()).isEqualTo(before + 1);
		jdbc.update("delete from fixture_spring_item where id = 100");
	}

	@Test
	@DisplayName("RAISE_APPLICATION_ERROR is not translated: it arrives with the routine's code and reason")
	void business_error() {
		assertThatThrownBy(fixture::raiseError)
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(20100);
					assertThat(e.getReason()).isEqualTo("rule violated");
					assertThat(e.getMessage()).contains("ORA-20100: rule violated");
				})
				.cause()
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("ORA-20100: rule violated");
	}

	@Test
	@DisplayName("a RAISE_APPLICATION_ERROR inside a transaction allows the rollback")
	void business_error_with_rollback() {
		int before = fixture.countItems();

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			fixture.insertItem(300);
			fixture.raiseError();
		})).isInstanceOf(PlsqlRoutineRaisedException.class);

		assertThat(fixture.countItems()).isEqualTo(before);
	}

	@Test
	@DisplayName("routines and JdbcTemplate in one transaction are committed together")
	void commit_together() {
		int before = fixture.countItems();
		try {
			transaction.executeWithoutResult(status -> {
				jdbc.update("insert into fixture_spring_item values (500)");
				fixture.insertItem(501);
				fixture.insertItem(502);
			});

			assertThat(fixture.countItems()).isEqualTo(before + 3);
		} finally {
			jdbc.update("delete from fixture_spring_item where id between 500 and 502");
		}
	}

	@Test
	@DisplayName("an exception that leaves the transaction rolls back the routines and the JdbcTemplate together")
	void roll_back_together() {
		int before = fixture.countItems();

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			jdbc.update("insert into fixture_spring_item values (520)");
			fixture.insertItem(521);
			fixture.raiseError();
		})).isInstanceOf(PlsqlRoutineRaisedException.class);

		assertThat(fixture.countItems()).isEqualTo(before);
	}

	@Test
	@DisplayName("a routine error caught inside the transaction undoes only that call, and the commit keeps the rest")
	void caught_error_inside_the_transaction() {
		int before = fixture.countItems();
		try {
			transaction.executeWithoutResult(status -> {
				fixture.insertItem(510);
				assertThatThrownBy(() -> fixture.insertItem(510))
						.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1));
				jdbc.update("insert into fixture_spring_item values (511)");
			});

			assertThat(fixture.countItems()).isEqualTo(before + 2);
		} finally {
			jdbc.update("delete from fixture_spring_item where id in (510, 511)");
		}
	}

	@Test
	@DisplayName("a COMMIT inside the routine commits the transaction so far: Spring's rollback comes too late")
	void routine_commits() {
		int before = fixture.countItems();
		try {
			transaction.executeWithoutResult(status -> {
				jdbc.update("insert into fixture_spring_item values (530)");
				fixture.insertAndCommit(531);
				jdbc.update("insert into fixture_spring_item values (532)");
				status.setRollbackOnly();
			});

			assertThat(fixture.countItems()).as("530 and 531 stay, 532 is rolled back").isEqualTo(before + 2);
		} finally {
			jdbc.update("delete from fixture_spring_item where id between 530 and 532");
		}
	}

	@Test
	@DisplayName("a ROLLBACK inside the routine undoes the transaction so far, and Spring commits the rest without an error")
	void routine_rolls_back() {
		int before = fixture.countItems();
		try {
			transaction.executeWithoutResult(status -> {
				jdbc.update("insert into fixture_spring_item values (540)");
				fixture.insertAndRollback(541);
				jdbc.update("insert into fixture_spring_item values (542)");
			});

			assertThat(fixture.countItems()).as("only 542 is committed").isEqualTo(before + 1);
		} finally {
			jdbc.update("delete from fixture_spring_item where id between 540 and 542");
		}
	}

	@Test
	@DisplayName("a ROLLBACK inside the routine erases the savepoint of a nested transaction: when the nested work "
			+ "fails, Spring reports the lost savepoint instead of the failure")
	void routine_rolls_back_in_nested_transaction() {
		TransactionTemplate nested = new TransactionTemplate(transaction.getTransactionManager());
		nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
		int before = fixture.countItems();
		try {
			transaction.executeWithoutResult(outer -> {
				jdbc.update("insert into fixture_spring_item values (550)");

				assertThatThrownBy(() -> nested.executeWithoutResult(inner -> {
					fixture.insertAndRollback(551);
					throw new IllegalStateException("the nested work fails");
				}))
						.isInstanceOf(TransactionSystemException.class)
						.hasMessage("Could not roll back to JDBC savepoint")
						.rootCause().hasMessageContaining("ORA-01086");

				jdbc.update("insert into fixture_spring_item values (552)");
			});

			assertThat(fixture.countItems()).as("550 was rolled back by the routine; only 552 is committed")
					.isEqualTo(before + 1);
		} finally {
			jdbc.update("delete from fixture_spring_item where id between 550 and 552");
		}
	}

	@Test
	@DisplayName("an uncategorized database failure is still an UncategorizedSQLException")
	void uncategorized_failure() {
		// the driver refuses the type before calling the database (ORA-17004), and Spring has no category for that
		assertThatThrownBy(() -> fixture.typeTheDriverRefuses(java.net.URI.create("urn:x")))
				.isInstanceOf(UncategorizedSQLException.class)
				.isNotInstanceOf(PlsqlRoutineRaisedException.class)
				.hasRootCauseInstanceOf(SQLException.class);
	}

	@Test
	@DisplayName("a duplicate key inside the routine is the routine's error, identified by its code")
	void duplicate_key() {
		fixture.insertItem(200);
		try {
			assertThatThrownBy(() -> fixture.insertItem(200))
					.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1));
		} finally {
			jdbc.update("delete from fixture_spring_item where id = 200");
		}
	}

	@Test
	@DisplayName("converters work the same way inside the transaction")
	void converters() {
		record Name(String value) {
		}
		@PlsqlPackage("FIXTURE_SPRING")
		interface WithConverter {
			@PlsqlRoutine("ECHO_NAMES")
			List<Name> echoNames(List<Name> value);
		}
		WithConverter withConverter = SpringPlsqlPackages.create(WithConverter.class, dataSource,
				PlsqlConverters.of(PlsqlConverter.ofNullable(Name.class, String.class, Name::value, Name::new)));

		transaction.executeWithoutResult(status -> assertThat(withConverter.echoNames(List.of(new Name("a"))))
				.containsExactly(new Name("a")));
	}

	@Test
	@DisplayName("with settings and no translator in them, failures still arrive in the Spring hierarchy")
	void settings_keep_spring_translation() {
		Fixture withSettings = SpringPlsqlPackages.create(Fixture.class, dataSource,
				PlsqlSettings.defaults().withBind(PlsqlBind.BY_POSITION));

		assertThatThrownBy(withSettings::sqlInvalid).isInstanceOf(BadSqlGrammarException.class);
		assertThatThrownBy(withSettings::raiseError).isInstanceOf(PlsqlRoutineRaisedException.class);
	}

	@Test
	@DisplayName("a translator in the settings replaces the Spring one")
	void settings_translator_replaces_spring() {
		Fixture withTranslator = SpringPlsqlPackages.create(Fixture.class, dataSource,
				PlsqlSettings.defaults().withExceptionTranslator(failure -> new IllegalStateException("custom")));

		assertThatThrownBy(withTranslator::sqlInvalid).isInstanceOf(IllegalStateException.class).hasMessage("custom");
	}

	@Test
	@DisplayName("a view (PlsqlPackages.using) of a Spring interface keeps Spring's exceptions, and routine errors as they are")
	void view_keeps_spring_translation() throws SQLException {
		try (java.sql.Connection connection = TestDatabase.newDataSource().getConnection()) {
			Fixture view = PlsqlPackages.using(fixture, connection);

			assertThatThrownBy(view::sqlInvalid).isInstanceOf(BadSqlGrammarException.class);
			assertThatThrownBy(() -> view.typeTheDriverRefuses(java.net.URI.create("urn:x")))
					.isInstanceOf(UncategorizedSQLException.class);
			assertThatThrownBy(view::raiseError)
					.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(20100));
			assertThat(connection.isClosed()).as("the view never closes the connection").isFalse();
		}
	}

	@Test
	@DisplayName("a view keeps Spring's exceptions for a lock too: PessimisticLockingFailureException")
	void view_keeps_spring_translation_for_a_lock() throws SQLException {
		try (java.sql.Connection connection = TestDatabase.newDataSource().getConnection()) {
			Fixture view = PlsqlPackages.using(fixture, connection);

			transaction.executeWithoutResult(status -> {
				jdbc.execute("LOCK TABLE FIXTURE_SPRING_ITEM IN EXCLUSIVE MODE");
				status.setRollbackOnly();

				assertThatThrownBy(view::lockTable).isInstanceOf(PessimisticLockingFailureException.class);
			});
		}
	}

	@Test
	@DisplayName("inside a Spring transaction, a view runs on the connection it was given, not on the transaction's")
	void view_ignores_the_spring_transaction() throws SQLException {
		try (java.sql.Connection connection = TestDatabase.newDataSource().getConnection()) {
			Fixture view = PlsqlPackages.using(fixture, connection);
			int viewSession = view.sessionId();

			transaction.executeWithoutResult(status -> {
				assertThat(view.sessionId()).as("the view's connection").isEqualTo(viewSession);
				assertThat(fixture.sessionId()).as("the interface itself: the transaction's").isEqualTo(currentSession())
						.isNotEqualTo(viewSession);
			});
		}
	}

	@Test
	@DisplayName("a locked resource stays in the Spring hierarchy, for whoever wants to retry")
	void locked_resource() {
		transaction.executeWithoutResult(status -> {
			jdbc.execute("LOCK TABLE FIXTURE_SPRING_ITEM IN EXCLUSIVE MODE");
			status.setRollbackOnly();

			java.util.concurrent.CompletableFuture<Void> otherSession = java.util.concurrent.CompletableFuture
					.runAsync(fixture::lockTable);

			assertThat(otherSession).failsWithin(java.time.Duration.ofSeconds(30))
					.withThrowableOfType(java.util.concurrent.ExecutionException.class)
					.withCauseInstanceOf(PessimisticLockingFailureException.class);
		});
	}

	@Test
	@DisplayName("invalid SQL arrives as BadSqlGrammarException, and the message includes the statement")
	void invalid_sql() {
		assertThatThrownBy(fixture::sqlInvalid)
				.isInstanceOf(BadSqlGrammarException.class)
				.hasMessageContaining("FIXTURE_SPRING.SQL_INVALID");
	}

	@Test
	@DisplayName("a mapping error is still a PlsqlMappingException, because it did not come from the database")
	void mapping_error() {
		assertThatThrownBy(() -> SpringPlsqlPackages.create(NonexistentRoutine.class, dataSource).doesNotExist())
				.isInstanceOf(PlsqlMappingException.class);
		assertThatThrownBy(() -> SpringPlsqlPackages.create(Invalid.class, dataSource).anything())
				.isInstanceOf(PlsqlMappingException.class);
	}
}
