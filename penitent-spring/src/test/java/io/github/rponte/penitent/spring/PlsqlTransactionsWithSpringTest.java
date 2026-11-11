package io.github.rponte.penitent.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlTransactions;

/**
 * In a Spring application, Spring owns the transactions: {@code PlsqlTransactions} works side by side
 * with them, never inside one of them nor around one of them. Each overlap would otherwise split the
 * work into two transactions that commit apart, without a word.
 */
class PlsqlTransactionsWithSpringTest {

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;

	@PlsqlPackage("FIXTURE_SPRING")
	interface Fixture {
		int sessionId();

		void insertItem(int id);
	}

	/** A Spring application: @EnablePlsqlPackages is what refuses a Spring transaction inside a block. */
	@Configuration
	@EnablePlsqlPackages("io.github.rponte.penitent.spring.scan")
	static class Application {

		@Bean
		DataSource dataSource() {
			return dataSource;
		}

		@Bean
		DataSourceTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		Fixture fixture(DataSource dataSource) {
			return SpringPlsqlPackages.create(Fixture.class, dataSource);
		}

		@Bean
		TransactionTemplate transactionTemplate(DataSourceTransactionManager transactionManager) {
			return new TransactionTemplate(transactionManager);
		}
	}

	@BeforeAll
	static void startDatabase() {
		dataSource = TestDatabase.dataSource();
		jdbc = new JdbcTemplate(dataSource);
	}

	@AfterEach
	void deleteItems() {
		jdbc.update("delete from fixture_spring_item where id between 3000 and 3099");
	}

	private final PlsqlTransactions transactions = PlsqlTransactions.of(dataSource);
	private final Fixture fixture = SpringPlsqlPackages.create(Fixture.class, dataSource);

	/** The page that shows the refusal; the build runs from the module's directory. */
	private static String transactionsPage() throws IOException {
		return Files.readString(Path.of("../docs/transactions.md"));
	}

	private static boolean exists(int id) {
		return jdbc.queryForObject("select count(*) from fixture_spring_item where id = ?", Integer.class, id) > 0;
	}

	@Test
	@DisplayName("side by side: a block outside any Spring transaction commits, and Spring's work after it too")
	void side_by_side() {
		TransactionTemplate spring = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

		transactions.run(tx -> tx.using(fixture).insertItem(3000));
		spring.executeWithoutResult(status -> fixture.insertItem(3001));

		assertThat(exists(3000)).isTrue();
		assertThat(exists(3001)).isTrue();
	}

	@Test
	@DisplayName("a block inside a Spring transaction is refused before it runs, and Spring's rollback undoes all")
	void block_inside_spring_transaction() {
		TransactionTemplate spring = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		AtomicBoolean ran = new AtomicBoolean();

		spring.executeWithoutResult(status -> {
			fixture.insertItem(3010);
			assertThatThrownBy(() -> transactions.run(tx -> {
				ran.set(true);
				tx.using(fixture).insertItem(3011);
			}))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("a Spring transaction is active on this thread")
					.hasMessageContaining("@Transactional")
					.satisfies(e -> assertThat(transactionsPage()).as("docs/transactions.md shows the message")
							.contains(e.getMessage()));
			status.setRollbackOnly();
		});

		assertThat(ran).as("the block never ran").isFalse();
		assertThat(exists(3010)).isFalse();
		assertThat(exists(3011)).isFalse();
	}

	@Test
	@DisplayName("a Spring transaction inside a block is refused before it begins, and the block rolls back")
	void spring_transaction_inside_block() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			TransactionTemplate spring = context.getBean(TransactionTemplate.class);
			Fixture bean = context.getBean(Fixture.class);
			AtomicBoolean ran = new AtomicBoolean();

			assertThatThrownBy(() -> transactions.run(tx -> {
				tx.using(bean).insertItem(3020);
				spring.executeWithoutResult(status -> {
					ran.set(true);
					jdbc.update("insert into fixture_spring_item values (3021)");
				});
			}))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("a PlsqlTransaction is open on this thread")
					.hasMessageContaining("@Transactional");

			assertThat(ran).as("the Spring transaction never began").isFalse();
			assertThat(exists(3020)).isFalse();
			assertThat(exists(3021)).isFalse();
		}
	}

	@Test
	@DisplayName("the refusal of a Spring transaction inside a block is registered once on each transaction manager")
	void refusal_registered_once() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			DataSourceTransactionManager manager = context.getBean(DataSourceTransactionManager.class);

			assertThat(manager.getTransactionExecutionListeners())
					.filteredOn(listener -> listener.getClass().getName().startsWith("io.github.rponte.penitent."))
					.hasSize(1);
		}
	}

	@Test
	@DisplayName("a Spring interface of the block's DataSource, called without tx.using, is refused")
	void spring_interface_outside_the_block() {
		AtomicBoolean ran = new AtomicBoolean();

		assertThatThrownBy(() -> transactions.run(tx -> {
			tx.using(fixture).insertItem(3030);
			fixture.insertItem(3031);
			ran.set(true);
		}))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("call it through tx.using(...)");

		assertThat(ran).isFalse();
		assertThat(exists(3030)).isFalse();
		assertThat(exists(3031)).isFalse();
	}

	@Test
	@DisplayName("a Spring interface called through tx.using runs in the block's session")
	void spring_interface_through_the_block() {
		transactions.run(tx -> {
			int blockSession = new JdbcTemplate(new SingleConnectionDataSource(tx.connection(), true))
					.queryForObject("select sys_context('userenv', 'sid') from dual", Integer.class);

			assertThat(tx.using(fixture).sessionId()).isEqualTo(blockSession);
		});
	}

	@Test
	@DisplayName("a Spring interface of another DataSource stays allowed inside the block, as in the core")
	void spring_interface_of_another_data_source() {
		Fixture other = SpringPlsqlPackages.create(Fixture.class, TestDatabase.newDataSource());

		transactions.run(tx -> {
			tx.using(fixture).insertItem(3040);
			other.insertItem(3041);
		});

		assertThat(exists(3040)).isTrue();
		assertThat(exists(3041)).isTrue();
	}
}
