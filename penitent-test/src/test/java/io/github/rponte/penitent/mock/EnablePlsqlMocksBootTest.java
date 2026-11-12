package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.mock.internal.EditionDataSource;
import io.github.rponte.penitent.mock.spring.EnablePlsqlMocks;

/**
 * {@code @EnablePlsqlMocks} in a Spring Boot test: the DataSource Spring Boot creates, a Hikari pool, is wrapped, and
 * the JdbcTemplate and the transaction manager it configures use the wrapped one.
 */
class EnablePlsqlMocksBootTest {

	static String url;

	@BeforeAll
	static void startDatabase() {
		TestDatabase.pool();
		url = TestDatabase.oracle().jdbcUrl();
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	static class Application {
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringBootTest(classes = Application.class)
	@EnablePlsqlMocks
	@Transactional
	static class BootSample {

		@DynamicPropertySource
		static void database(DynamicPropertyRegistry registry) {
			registry.add("spring.datasource.url", () -> url);
			registry.add("spring.datasource.username", () -> OracleUnderTest.USER);
			registry.add("spring.datasource.password", () -> OracleUnderTest.PASSWORD);
		}

		@Autowired ApplicationContext context;
		@Autowired PlsqlMocks mocks;
		@Autowired JdbcTemplate jdbc;

		@Test
		void mocks_through_the_data_source_of_spring_boot() throws Exception {
			DataSource dataSource = context.getBean(DataSource.class);
			assertThat(dataSource).isInstanceOf(EditionDataSource.class);
			assertThat(dataSource.unwrap(HikariDataSource.class)).as("the pool, through unwrap").isNotNull();
			assertThat(context.getBeansOfType(HikariDataSource.class)).as("no longer a bean of the pool's type").isEmpty();
			assertThat(context.getBean(PlatformTransactionManager.class)).isNotNull();

			when(mocks.mock(EnablePlsqlMocksTest.Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

			assertThat(jdbc.queryForObject("SELECT FREIGHTS.CALCULATE('SP', 'CE') FROM DUAL", BigDecimal.class))
					.isEqualByComparingTo("24.90");
		}
	}

	@Test
	@DisplayName("Spring Boot's DataSource is wrapped, and its JdbcTemplate, in the test transaction, sees the mock")
	void spring_boot() {
		var events = TestKit.run(BootSample.class);
		if (!events.failed().list().isEmpty()) {
			throw new AssertionError(TestKit.firstFailure(events));
		}
		events.assertStatistics(stats -> stats.started(1).succeeded(1));
		assertThat(TestKit.editionsLeft(TestDatabase.pool())).isZero();
	}
}
