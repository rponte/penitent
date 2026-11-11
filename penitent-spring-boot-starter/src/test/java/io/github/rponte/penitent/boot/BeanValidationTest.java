package io.github.rponte.penitent.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolationException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import io.github.rponte.penitent.boot.validation.Customers;
import io.github.rponte.penitent.boot.validation.ValidationApplication;

/**
 * Bean Validation on an interface, with the method validation Spring Boot sets up: an invalid argument
 * is refused before the call takes a connection, and a valid one goes on to the database.
 */
class BeanValidationTest {

	private final CountingDataSource database = new CountingDataSource();

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class, ValidationAutoConfiguration.class))
			.withUserConfiguration(ValidationApplication.class)
			.withBean(DataSource.class, database::dataSource);

	@Test
	@DisplayName("an invalid argument is refused before the call reaches the database")
	void invalid_argument() {
		runner.run(context -> {
			Customers customers = context.getBean(Customers.class);

			assertThatThrownBy(() -> customers.rename(-1, " "))
					.isInstanceOf(ConstraintViolationException.class)
					.satisfies(e -> assertThat(((ConstraintViolationException) e).getConstraintViolations())
							.extracting(violation -> violation.getPropertyPath().toString())
							.containsExactlyInAnyOrder("rename.id", "rename.name"));

			assertThat(database.connectionsAsked()).isZero();
		});
	}

	@Test
	@DisplayName("@Valid checks the fields of a record argument, before the call")
	void cascaded_record() {
		runner.run(context -> {
			Customers customers = context.getBean(Customers.class);

			assertThatThrownBy(() -> customers.register(new Customers.Customer("", 0)))
					.isInstanceOf(ConstraintViolationException.class)
					.satisfies(e -> assertThat(((ConstraintViolationException) e).getConstraintViolations())
							.extracting(violation -> violation.getPropertyPath().toString())
							.containsExactlyInAnyOrder("register.customer.name", "register.customer.age"));

			assertThat(database.connectionsAsked()).isZero();
		});
	}

	@Test
	@DisplayName("a valid argument goes on to the database")
	void valid_argument() {
		runner.run(context -> {
			Customers customers = context.getBean(Customers.class);

			assertThatThrownBy(() -> customers.rename(1, "Ana"))
					.isNotInstanceOf(ConstraintViolationException.class)
					.hasStackTraceContaining("database is down");

			assertThat(database.connectionsAsked()).isEqualTo(1);
		});
	}
}
