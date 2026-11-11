package io.github.rponte.penitent.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolationException;

import java.sql.Connection;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlTransactions;
import io.github.rponte.penitent.spring.scanwrapped.WrappedItems;

/**
 * An interface Spring wraps in its own proxy, for {@code @Transactional}, {@code @Validated} or advice of
 * the application, is still the library's: {@code validate} and the views of {@code tx.using} and
 * {@code PlsqlPackages.using} see through Spring's layers. A view keeps every layer, so validation and the
 * application's advice still apply, except Spring's transaction: on a view, the transaction belongs to
 * the block, or to whoever owns the connection.
 */
class SpringWrappedInterfacesTest {

	private static DataSource dataSource;
	private static JdbcTemplate jdbc;

	/** Calls through the application's own advice, around Spring's proxy: a second layer. */
	static final AtomicInteger ADVISED_CALLS = new AtomicInteger();

	@Configuration
	@EnableTransactionManagement
	@EnablePlsqlPackages(value = "io.github.rponte.penitent.spring.scanwrapped", validateOnStartup = true)
	static class Application {

		@Bean
		static MethodValidationPostProcessor methodValidation() {
			return new MethodValidationPostProcessor();
		}

		@Bean
		DataSource dataSource() {
			return dataSource;
		}

		@Bean
		DataSourceTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}
	}

	@BeforeAll
	static void startDatabase() {
		dataSource = TestDatabase.dataSource();
		jdbc = new JdbcTemplate(dataSource);
	}

	@AfterEach
	void deleteItems() {
		jdbc.update("delete from fixture_spring_item where id between 5000 and 5099");
		ADVISED_CALLS.set(0);
	}

	private static boolean exists(int id) {
		return jdbc.queryForObject("select count(*) from fixture_spring_item where id = ?", Integer.class, id) > 0;
	}

	/** The bean, wrapped once more by advice of the application, which counts the calls. */
	private static WrappedItems advised(WrappedItems bean) {
		ProxyFactory factory = new ProxyFactory(bean);
		factory.addInterface(WrappedItems.class);
		factory.addAdvice((MethodInterceptor) invocation -> {
			ADVISED_CALLS.incrementAndGet();
			return invocation.proceed();
		});
		return (WrappedItems) factory.getProxy();
	}

	private static int sessionOf(Connection connection) {
		return new JdbcTemplate(new SingleConnectionDataSource(connection, true))
				.queryForObject("select sys_context('userenv', 'sid') from dual", Integer.class);
	}

	@Test
	@DisplayName("validateOnStartup validates an interface Spring wrapped, and the application starts")
	void validate_on_startup() {
		assertThatCode(() -> new AnnotationConfigApplicationContext(Application.class).close())
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("PlsqlPackages.validate sees through Spring's layers")
	void validate() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			WrappedItems bean = context.getBean(WrappedItems.class);

			assertThatCode(() -> PlsqlPackages.validate(bean)).doesNotThrowAnyException();
			assertThatCode(() -> PlsqlPackages.validate(advised(bean))).doesNotThrowAnyException();
		}
	}

	@Test
	@DisplayName("tx.using: the view runs in the block, without Spring's transaction, and the block's rollback undoes it")
	void view_in_block() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			WrappedItems bean = context.getBean(WrappedItems.class);
			AtomicReference<Integer> blockSession = new AtomicReference<>();
			AtomicReference<Integer> viewSession = new AtomicReference<>();

			assertThatThrownBy(() -> PlsqlTransactions.of(dataSource).run(tx -> {
				WrappedItems view = tx.using(bean);
				view.insert(5000);
				blockSession.set(sessionOf(tx.connection()));
				viewSession.set(view.sessionId());
				throw new IllegalStateException("block fails");
			})).hasMessage("block fails");

			assertThat(viewSession.get()).isEqualTo(blockSession.get());
			assertThat(exists(5000)).as("rolled back with the block").isFalse();
		}
	}

	@Test
	@DisplayName("tx.using: the view keeps validation, which refuses before the call")
	void view_keeps_validation() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			WrappedItems bean = context.getBean(WrappedItems.class);

			assertThatThrownBy(() -> PlsqlTransactions.of(dataSource).run(tx -> tx.using(bean).insert(-1)))
					.isInstanceOf(ConstraintViolationException.class);
		}
	}

	@Test
	@DisplayName("tx.using: the view keeps the application's own advice, through every layer")
	void view_keeps_application_advice() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			WrappedItems advised = advised(context.getBean(WrappedItems.class));

			PlsqlTransactions.of(dataSource).run(tx -> tx.using(advised).insert(5010));

			assertThat(ADVISED_CALLS).hasValue(1);
			assertThat(exists(5010)).isTrue();
		}
	}

	@Test
	@DisplayName("tx.using: the view stops working when the block ends, through Spring's layers")
	void view_after_block() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			WrappedItems bean = context.getBean(WrappedItems.class);
			AtomicReference<WrappedItems> view = new AtomicReference<>();

			PlsqlTransactions.of(dataSource).run(tx -> view.set(tx.using(bean)));

			assertThatThrownBy(() -> view.get().sessionId()).isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	@DisplayName("PlsqlPackages.using: the view runs on the connection given, in its transaction, not in Spring's")
	void view_on_connection() throws Exception {
		try (var context = new AnnotationConfigApplicationContext(Application.class);
				Connection connection = dataSource.getConnection()) {
			WrappedItems view = PlsqlPackages.using(context.getBean(WrappedItems.class), connection);
			connection.setAutoCommit(false);

			view.insert(5020);
			assertThat(view.sessionId()).isEqualTo(sessionOf(connection));
			connection.rollback();

			assertThat(exists(5020)).as("rolled back with the connection").isFalse();
		}
	}
}
