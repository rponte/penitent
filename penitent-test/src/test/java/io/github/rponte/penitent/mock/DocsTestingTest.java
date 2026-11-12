package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.isNull;
import static io.github.rponte.penitent.mock.PlsqlMocks.never;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.times;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;
import io.github.rponte.penitent.spring.SpringPlsqlPackages;

/** The examples of docs/testing.md, run against Oracle: each region is a block of the page. */
class DocsTestingTest {

	// snippet: testing/interface
	@PlsqlPackage("FREIGHTS")
	interface Freights {
	    BigDecimal calculate(String pOrigin, String pDestination);

	    void quote(String pUf, PlsqlOut<BigDecimal> pValue);
	}
	// end-snippet

	static DataSource pool;
	static DataSource dataSource;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		dataSource = PlsqlMocks.wrap(pool);
	}

	@Test
	@DisplayName("testing.md: prepare, on a disposable database")
	void prepare() {
		DataSource admin = TestDatabase.newPool(TestDatabase.oracle().adminUser());
		DataSource pool = DocsTestingTest.pool;
		// snippet: testing/prepare
		PlsqlMocks.prepare(admin, pool, "PLSQL_TEST");   // admin: a DBA's DataSource; pool: the schema's own
		// end-snippet
	}

	@Test
	@DisplayName("testing.md: mocks without JUnit's extension")
	void without_extension() {
		DataSource pool = DocsTestingTest.pool;
		// snippet: testing/without-extension
		DataSource dataSource = PlsqlMocks.wrap(pool);                        // what the application uses in tests
		Freights freights = PlsqlPackages.create(Freights.class, dataSource);

		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
		    mocks.begin();                                                    // the test's edition

		    Freights mock = mocks.mock(Freights.class);
		    when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

		    assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
		    verify(mock).calculate("SP", "CE");
		}                                                                     // end(): the edition is dropped
		// end-snippet
	}

	/** The stubbing examples, each in a test with an edition of its own. */
	@Nested
	class Stubbing {

			private PlsqlMocks mocks;
		private Freights mock;
		private Freights freights;

		@BeforeEach
		void begin() {
			mocks = PlsqlMocks.on(dataSource);
			mocks.begin();
			mock = mocks.mock(Freights.class);
			freights = PlsqlPackages.create(Freights.class, dataSource);
		}

		@AfterEach
		void end() {
			mocks.end();
		}

		@Test
		@DisplayName("testing.md: errors")
		void errors() {
			// snippet: testing/errors
			when(mock.calculate("SP", "XX")).thenThrow(-20200, "Cannot calculate freight to XX");
			when(mock.calculate("SP", "AM")).thenRaise("NO_DATA_FOUND");
			// end-snippet

			assertThatThrownBy(() -> freights.calculate("SP", "XX")).isInstanceOfSatisfying(
					PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(20200));
			assertThatThrownBy(() -> freights.calculate("SP", "AM")).isInstanceOfSatisfying(
					PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1403));
		}

		@Test
		@DisplayName("testing.md: a procedure with OUT")
		void procedure() {
			// snippet: testing/procedure
			when(() -> mock.quote(eq("CE"), out(new BigDecimal("99.99"))));
			// end-snippet

			PlsqlOut<BigDecimal> value = new PlsqlOut<>();
			freights.quote("CE", value);
			assertThat(value.get()).isEqualByComparingTo("99.99");
		}

		@Test
		@DisplayName("testing.md: matchers, the last stub that matches winning")
		void matchers() {
			// snippet: testing/matchers
			when(mock.calculate(eq("SP"), any())).thenReturn(new BigDecimal("10.00"));
			when(mock.calculate(eq("SP"), isNull())).thenReturn(new BigDecimal("15.00"));   // the last that matches wins
			// end-snippet

			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("10.00");
			assertThat(freights.calculate("SP", null)).isEqualByComparingTo("15.00");
		}

		@Test
		@DisplayName("testing.md: verify")
		void verifies() {
			when(mock.calculate(any(), any())).thenReturn(BigDecimal.ONE);
			freights.calculate("SP", "CE");
			freights.calculate("SP", "RJ");

			// snippet: testing/verify
			verify(mock).calculate("SP", "CE");
			verify(mock, times(2)).calculate(eq("SP"), any());
			verify(mock, never()).calculate("RJ", "BA");
			// end-snippet
		}
	}

	@Test
	@DisplayName("testing.md: the JUnit 5 example")
	void junit_example() {
		TestKit.run(FreightCalculationTest.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** The application of the Spring example: a DataSource, a transaction manager and the interface. */
	@Configuration
	@EnableTransactionManagement
	static class Application {

		@Bean(destroyMethod = "")
		DataSource dataSource() {
			return pool;
		}

		@Bean
		DataSourceTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		Freights freights(DataSource dataSource) {
			return SpringPlsqlPackages.create(Freights.class, dataSource);
		}
	}

	@Test
	@DisplayName("testing.md: the Spring example")
	void spring_example() {
		TestKit.run(FreightServiceTest.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}
}
