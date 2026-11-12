package io.github.rponte.penitent.mock.docs;

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
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;
import io.github.rponte.penitent.mock.PlsqlMocks;
import io.github.rponte.penitent.mock.TestDatabase;
import io.github.rponte.penitent.mock.TestKit;

/**
 * The examples of docs/testing.md, run against Oracle: each region is a block of the page. The examples that are a
 * whole test class, such as the Spring ones, are classes of this package, run here through JUnit's test kit.
 */
class DocsTestingTest {

	static DataSource pool;
	static DataSource dataSource;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		dataSource = PlsqlMocks.wrap(pool);
	}

	@Test
	@DisplayName("testing.md: calling the real routine")
	void real_routine() {
		DataSource dataSource = pool;
		// snippet: testing/real-call
		Freights freights = PlsqlPackages.create(Freights.class, dataSource);

		BigDecimal freight = freights.calculate("SP", "CE");

		// what the real PL/SQL computes: the rate of CE, 50, times 1.5
		assertThat(freight).isEqualByComparingTo("75.00");
		// end-snippet
	}

	@Test
	@DisplayName("testing.md: arrange, act, assert, rolled back by Spring")
	void arrange_act_assert() {
		TestKit.run(FreightReportTest.class).assertStatistics(stats -> stats.started(1).succeeded(1));

		assertThat(new JdbcTemplate(pool).queryForObject(
				"SELECT COUNT(*) FROM FREIGHT_ITEM WHERE ID IN (7001, 7002)", Integer.class))
				.as("rolled back").isZero();
	}

	@Test
	@DisplayName("testing.md: the first mock, with Spring")
	void first_mock() {
		TestKit.run(CheckoutServiceTest.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	@Test
	@DisplayName("testing.md: prepare, on a disposable database")
	void prepare() {
		DataSource admin = TestDatabase.adminPool();
		DataSource pool = DocsTestingTest.pool;
		// snippet: testing/prepare
		// admin: a DBA's DataSource; pool: the DataSource of the schema that owns the packages
		PlsqlMocks.prepare(admin, pool, "PLSQL_TEST");
		// end-snippet
	}

	@Test
	@DisplayName("testing.md: the JUnit 5 example")
	void junit_example() {
		TestKit.run(CheckoutTest.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	@Test
	@DisplayName("testing.md: mocks without JUnit's extension")
	void without_extension() {
		DataSource pool = DocsTestingTest.pool;
		// snippet: testing/without-extension
		// the DataSource the application uses in tests, wrapped
		DataSource dataSource = PlsqlMocks.wrap(pool);
		CheckoutService checkout = new CheckoutService(PlsqlPackages.create(Freights.class, dataSource));

		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
		    // begins the test: from here, stubs can be made
		    mocks.begin();

		    // arrange
		    Freights freights = mocks.mock(Freights.class);
		    when(freights.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

		    // act
		    BigDecimal total = checkout.total(new BigDecimal("100.00"), "SP", "CE");

		    // assert
		    assertThat(total).isEqualByComparingTo("124.90");
		}
		// end-snippet
	}

	/** The stubbing examples, each in a test of its own. */
	@Nested
	class Stubbing {

		private PlsqlMocks mocks;
		private Freights freights;
		private Freights application;

		@BeforeEach
		void begin() {
			mocks = PlsqlMocks.on(dataSource);
			mocks.begin();
			freights = mocks.mock(Freights.class);
			application = PlsqlPackages.create(Freights.class, dataSource);
		}

		@AfterEach
		void end() {
			mocks.end();
		}

		@Test
		@DisplayName("testing.md: errors")
		void errors() {
			// snippet: testing/errors
			when(freights.calculate("SP", "XX")).thenThrow(-20200, "Cannot calculate freight to XX");
			when(freights.calculate("SP", "AM")).thenRaise("NO_DATA_FOUND");
			// end-snippet

			assertThatThrownBy(() -> application.calculate("SP", "XX")).isInstanceOfSatisfying(
					PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(20200));
			assertThatThrownBy(() -> application.calculate("SP", "AM")).isInstanceOfSatisfying(
					PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1403));
		}

		@Test
		@DisplayName("testing.md: a procedure with OUT")
		void procedure() {
			// snippet: testing/procedure
			when(() -> freights.quote(eq("CE"), out(new BigDecimal("99.99"))));
			// end-snippet

			PlsqlOut<BigDecimal> value = new PlsqlOut<>();
			application.quote("CE", value);
			assertThat(value.get()).isEqualByComparingTo("99.99");
		}

		@Test
		@DisplayName("testing.md: matchers, the last stub that matches winning")
		void matchers() {
			// snippet: testing/matchers
			when(freights.calculate(eq("SP"), any())).thenReturn(new BigDecimal("10.00"));
			// the last stub that matches wins: this one, for a null destination
			when(freights.calculate(eq("SP"), isNull())).thenReturn(new BigDecimal("15.00"));
			// end-snippet

			assertThat(application.calculate("SP", "CE")).isEqualByComparingTo("10.00");
			assertThat(application.calculate("SP", null)).isEqualByComparingTo("15.00");
		}

		@Test
		@DisplayName("testing.md: verify")
		void verifies() {
			when(freights.calculate(any(), any())).thenReturn(BigDecimal.ONE);
			application.calculate("SP", "CE");
			application.calculate("SP", "RJ");

			// snippet: testing/verify
			verify(freights).calculate("SP", "CE");
			verify(freights, times(2)).calculate(eq("SP"), any());
			verify(freights, never()).calculate("RJ", "BA");
			// end-snippet
		}
	}
}
