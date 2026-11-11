package io.github.rponte.penitent.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.constraints.Negative;
import jakarta.validation.constraints.PositiveOrZero;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import io.github.rponte.penitent.PlsqlPackage;

/**
 * Constraints on what a routine returns, with Spring's method validation: checked after the call, on
 * the value the database sent back. The arguments are covered without a database, in the starter.
 */
class BeanValidationTest {

	@PlsqlPackage("FIXTURE_SPRING")
	@Validated
	interface Fixture {
		@PositiveOrZero
		int countItems();

		@Negative
		Integer sessionId();
	}

	@Configuration
	static class Application {

		@Bean
		static MethodValidationPostProcessor methodValidation() {
			return new MethodValidationPostProcessor();
		}

		@Bean
		Fixture fixture() {
			return SpringPlsqlPackages.create(Fixture.class, TestDatabase.dataSource());
		}
	}

	@Test
	@DisplayName("a value that meets the constraint on the return comes back as it is")
	void valid_return() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			assertThat(context.getBean(Fixture.class).countItems()).isNotNegative();
		}
	}

	@Test
	@DisplayName("a value that breaks the constraint on the return is refused after the call")
	void invalid_return() {
		try (var context = new AnnotationConfigApplicationContext(Application.class)) {
			assertThatThrownBy(() -> context.getBean(Fixture.class).sessionId())
					.isInstanceOf(ConstraintViolationException.class)
					.satisfies(e -> assertThat(((ConstraintViolationException) e).getConstraintViolations())
							.extracting(violation -> violation.getPropertyPath().toString())
							.containsExactly("sessionId.<return value>"));
		}
	}
}
