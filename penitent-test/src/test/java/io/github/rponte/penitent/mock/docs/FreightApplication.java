package io.github.rponte.penitent.mock.docs;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.github.rponte.penitent.mock.TestDatabase;
import io.github.rponte.penitent.spring.SpringPlsqlPackages;

/**
 * The Spring Boot application of the Testing page's examples, found by their {@code @SpringBootTest}. Its DataSource is
 * the module's test database; the rest is what Spring Boot configures.
 */
@SpringBootApplication
public class FreightApplication {

	@Bean(destroyMethod = "")   // the pool is shared by every test class of the module
	DataSource dataSource() {
		return TestDatabase.pool();
	}

	@Bean
	Freights freights(DataSource dataSource) {
		return SpringPlsqlPackages.create(Freights.class, dataSource);
	}

	@Bean
	CheckoutService checkoutService(Freights freights) {
		return new CheckoutService(freights);
	}
}
