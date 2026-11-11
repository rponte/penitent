package io.github.rponte.penitent.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import io.github.rponte.penitent.PlsqlTransactions;
import io.github.rponte.penitent.boot.app.Application;
import io.github.rponte.penitent.spring.EnablePlsqlPackages;

/**
 * A Spring Boot application, with the transaction manager and the {@code @Transactional} proxies Spring
 * Boot creates: {@code PlsqlTransactions} and Spring's transactions refuse to overlap there too. Both
 * refusals happen before any SQL, so a {@code DataSource} that hands out connections which only take a
 * transaction's calls is enough, and counts how many were taken.
 */
class PlsqlTransactionsWithBootTest {

	/** A service of the application, with a Spring transaction. */
	public static class Service {

		private final AtomicBoolean ran = new AtomicBoolean();

		/** Through a method: a field read on Spring's proxy would be the proxy's own, never set. */
		public boolean ran() {
			return ran.get();
		}

		@Transactional
		public void work() {
			ran.set(true);
		}

		@Transactional
		public void blockInside(PlsqlTransactions transactions) {
			transactions.run(tx -> ran.set(true));
		}
	}

	private final AtomicInteger connectionsTaken = new AtomicInteger();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class,
					DataSourceTransactionManagerAutoConfiguration.class,
					TransactionManagerCustomizationAutoConfiguration.class, TransactionAutoConfiguration.class))
			.withUserConfiguration(Application.class)
			.withBean(DataSource.class, this::counting)
			.withBean(Service.class);

	@Test
	@DisplayName("a @Transactional method called inside a block is refused before Spring takes a connection")
	void transactional_inside_block() {
		runner.run(context -> {
			Service service = context.getBean(Service.class);
			PlsqlTransactions transactions = PlsqlTransactions.of(context.getBean(DataSource.class));

			assertThatThrownBy(() -> transactions.run(tx -> service.work()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("a PlsqlTransaction is open on this thread");

			assertThat(service.ran()).as("the method never ran").isFalse();
			assertThat(connectionsTaken).as("only the block's connection").hasValue(1);
		});
	}

	@Test
	@DisplayName("a block inside a @Transactional method is refused before it takes a connection")
	void block_inside_transactional() {
		runner.run(context -> {
			Service service = context.getBean(Service.class);
			PlsqlTransactions transactions = PlsqlTransactions.of(context.getBean(DataSource.class));

			assertThatThrownBy(() -> service.blockInside(transactions))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("a Spring transaction is active on this thread");

			assertThat(service.ran()).as("the block never ran").isFalse();
			assertThat(connectionsTaken).as("only Spring's connection").hasValue(1);
		});
	}

	@Test
	@DisplayName("side by side: a block, then a @Transactional method, each in its own transaction")
	void side_by_side() {
		runner.run(context -> {
			Service service = context.getBean(Service.class);

			PlsqlTransactions.of(context.getBean(DataSource.class)).run(tx -> {
			});
			service.work();

			assertThat(service.ran()).isTrue();
			assertThat(connectionsTaken).hasValue(2);
		});
	}

	@Configuration
	@EnablePlsqlPackages("io.github.rponte.penitent.boot.app")
	static class WithAnnotation {
	}

	@Test
	@DisplayName("with @EnablePlsqlPackages as well, the refusal is still added once, and still refuses")
	void with_annotation() {
		runner.withUserConfiguration(WithAnnotation.class).run(context -> {
			assertThat(context.getBean(ConfigurableTransactionManager.class).getTransactionExecutionListeners())
					.filteredOn(listener -> listener.getClass().getName().startsWith("io.github.rponte.penitent."))
					.hasSize(1);

			Service service = context.getBean(Service.class);
			assertThatThrownBy(() -> PlsqlTransactions.of(context.getBean(DataSource.class))
					.run(tx -> service.work()))
					.hasMessageContaining("a PlsqlTransaction is open on this thread");
		});
	}

	/** Connections that take a transaction's calls and run no SQL; each one taken is counted. */
	private DataSource counting() {
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getConnection" -> {
						connectionsTaken.incrementAndGet();
						yield connection();
					}
					case "toString" -> "counting DataSource";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}

	private static Connection connection() {
		AtomicBoolean autoCommit = new AtomicBoolean(true);
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getAutoCommit" -> autoCommit.get();
					case "setAutoCommit" -> {
						autoCommit.set((Boolean) args[0]);
						yield null;
					}
					case "commit", "rollback", "close" -> null;
					case "isClosed", "isReadOnly" -> false;
					case "toString" -> "counting Connection";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
