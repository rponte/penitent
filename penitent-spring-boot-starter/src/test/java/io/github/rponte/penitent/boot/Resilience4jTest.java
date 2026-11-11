package io.github.rponte.penitent.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.springboot.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot.retry.autoconfigure.RetryAutoConfiguration;
import io.github.rponte.penitent.boot.resilience.Payments;
import io.github.rponte.penitent.boot.resilience.ResilienceApplication;

/**
 * Resilience4j's annotations on an interface, with its Spring Boot autoconfiguration: each attempt is
 * a call, which asks the database, here down, for a connection.
 */
class Resilience4jTest {

	private final CountingDataSource database = new CountingDataSource();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class, AopAutoConfiguration.class,
					RetryAutoConfiguration.class, CircuitBreakerAutoConfiguration.class))
			.withUserConfiguration(ResilienceApplication.class)
			.withBean(DataSource.class, database::dataSource)
			.withPropertyValues(
					"resilience4j.retry.instances.payments.max-attempts=3",
					"resilience4j.retry.instances.payments.wait-duration=1ms",
					"resilience4j.circuitbreaker.instances.payments.sliding-window-size=2",
					"resilience4j.circuitbreaker.instances.payments.minimum-number-of-calls=2");

	@Test
	@DisplayName("@Retry calls the routine again, up to the attempts configured, then gives up with the failure")
	void retry() {
		runner.run(context -> {
			Payments payments = context.getBean(Payments.class);

			assertThatThrownBy(() -> payments.pay(1)).hasStackTraceContaining("database is down");

			assertThat(database.connectionsAsked()).isEqualTo(3);
		});
	}

	@Test
	@DisplayName("@CircuitBreaker opens after the failures configured, and then refuses without calling")
	void circuit_breaker() {
		runner.run(context -> {
			Payments payments = context.getBean(Payments.class);

			assertThatThrownBy(() -> payments.refund(1)).hasStackTraceContaining("database is down");
			assertThatThrownBy(() -> payments.refund(1)).hasStackTraceContaining("database is down");
			assertThatThrownBy(() -> payments.refund(1)).isInstanceOf(CallNotPermittedException.class);

			assertThat(database.connectionsAsked()).isEqualTo(2);
		});
	}
}
