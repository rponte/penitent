package io.github.rponte.penitent.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlTransactions;

/**
 * {@code @Transactional} on the methods of an interface, as on any Spring bean: alone, the call runs in
 * a transaction of its own and commits; inside another, it takes part, or starts a new one with
 * {@code REQUIRES_NEW}.
 */
class TransactionalInterfaceTest {

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;

	@PlsqlPackage("FIXTURE_SPRING")
	interface Items {

		@Transactional
		@PlsqlRoutine("INSERT_ITEM")
		void insert(int id);

		@Transactional(propagation = Propagation.REQUIRES_NEW)
		@PlsqlRoutine("INSERT_ITEM")
		void insertInNewTransaction(int id);
	}

	@Configuration
	@EnableTransactionManagement
	@EnablePlsqlPackages(value = "io.github.rponte.penitent.spring.scan", validateOnStartup = false)
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
		TransactionTemplate transactionTemplate(DataSourceTransactionManager transactionManager) {
			return new TransactionTemplate(transactionManager);
		}

		@Bean
		Items items(DataSource dataSource) {
			return SpringPlsqlPackages.create(Items.class, dataSource);
		}
	}

	@BeforeAll
	static void startDatabase() {
		dataSource = TestDatabase.dataSource();
		jdbc = new JdbcTemplate(dataSource);
	}

	@AfterEach
	void deleteItems() {
		jdbc.update("delete from fixture_spring_item where id between 4000 and 4099");
	}

	private static boolean exists(int id) {
		return jdbc.queryForObject("select count(*) from fixture_spring_item where id = ?", Integer.class, id) > 0;
	}

	@Test
	@DisplayName("alone, the call runs in a transaction of its own, committed when it returns")
	void alone() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			context.getBean(Items.class).insert(4000);

			assertThat(exists(4000)).isTrue();
		}
	}

	@Test
	@DisplayName("inside another transaction, the call takes part in it, and its rollback undoes the call")
	void takes_part() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			Items items = context.getBean(Items.class);

			context.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
				items.insert(4010);
				status.setRollbackOnly();
			});

			assertThat(exists(4010)).isFalse();
		}
	}

	@Test
	@DisplayName("with REQUIRES_NEW, the call commits on its own, even when the transaction around it rolls back")
	void requires_new() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			Items items = context.getBean(Items.class);

			context.getBean(TransactionTemplate.class).executeWithoutResult(status -> {
				items.insertInNewTransaction(4020);
				status.setRollbackOnly();
			});

			assertThat(exists(4020)).isTrue();
		}
	}

	@Test
	@DisplayName("called directly inside a PlsqlTransactions block, it is refused before its transaction begins")
	void inside_block() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			Items items = context.getBean(Items.class);

			assertThatThrownBy(() -> PlsqlTransactions.of(dataSource).run(tx -> items.insert(4030)))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("a PlsqlTransaction is open on this thread");

			assertThat(exists(4030)).isFalse();
		}
	}
}
