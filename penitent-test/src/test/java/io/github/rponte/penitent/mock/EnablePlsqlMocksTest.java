package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.mock.internal.EditionDataSource;
import io.github.rponte.penitent.mock.spring.EnablePlsqlMocks;
import io.github.rponte.penitent.spring.SpringPlsqlPackages;

/**
 * {@code @EnablePlsqlMocks} in Spring tests, run through JUnit's test kit: the context's {@code DataSource} is
 * wrapped, the edition begins before Spring's test transaction and ends after it, and compiling a stub never commits
 * that transaction.
 */
class EnablePlsqlMocksTest {

	@PlsqlPackage("FREIGHTS")
	public interface Freights {
		BigDecimal calculate(String pOrigin, String pDestination);
	}

	static DataSource pool;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
	}

	/** An application: its DataSource, a transaction manager, a JdbcTemplate and an interface that joins transactions. */
	@Configuration
	@EnableTransactionManagement
	static class Application {

		@Bean(destroyMethod = "")   // the pool is shared by every test class of the module
		DataSource dataSource() {
			return pool;
		}

		@Bean
		DataSourceTransactionManager transactionManager(DataSource dataSource) {
			return new DataSourceTransactionManager(dataSource);
		}

		@Bean
		JdbcTemplate jdbcTemplate(DataSource dataSource) {
			return new JdbcTemplate(dataSource);
		}

		@Bean
		Freights freights(DataSource dataSource) {
			return SpringPlsqlPackages.create(Freights.class, dataSource);
		}
	}

	private static int itemsWithId(int id) {
		return new JdbcTemplate(pool).queryForObject("SELECT COUNT(*) FROM FREIGHT_ITEM WHERE ID = ?", Integer.class, id);
	}

	/** A transactional test that writes before it stubs, so a stub that committed would leave the row behind. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(Application.class)
	@EnablePlsqlMocks
	@Transactional
	static class TransactionalSample {

		@Autowired PlsqlMocks mocks;
		@Autowired Freights freights;
		@Autowired JdbcTemplate jdbc;

		@Test
		void stubs_inside_the_transaction() {
			jdbc.update("INSERT INTO FREIGHT_ITEM (ID, UF) VALUES (9100, 'SP')");

			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
			assertThat(jdbc.queryForObject("SELECT FREIGHTS.CALCULATE('SP', 'CE') FROM DUAL", BigDecimal.class))
					.isEqualByComparingTo("24.90");
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FREIGHT_ITEM WHERE ID = 9100", Integer.class))
					.as("the transaction's own row, still there").isEqualTo(1);
		}
	}

	@Test
	@DisplayName("in a transactional test, the mock is seen in the transaction, and compiling it commits nothing")
	void transactional_test() {
		TestKit.run(TransactionalSample.class).assertStatistics(stats -> stats.started(1).succeeded(1));

		assertThat(itemsWithId(9100)).as("rolled back with the test").isZero();
		assertThat(TestKit.editionsLeft(pool)).isZero();
	}

	/** Without a transaction: two tests, the first stubs, the second sees the real routine; and one fails. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(Application.class)
	@EnablePlsqlMocks
	@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
	static class WithoutTransactionSample {

		@Autowired PlsqlMocks mocks;
		@Autowired Freights freights;

		@Test
		@Order(1)
		void stubs() {
			Freights mock = mocks.mock(Freights.class);
			when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
			verify(mock, PlsqlMocks.times(2)).calculate("SP", "CE");
		}

		@Test
		@Order(2)
		void sees_the_real_routine() {
			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("75");
		}

		@Test
		@Order(3)
		void fails() {
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(BigDecimal.ONE);
			throw new AssertionError("the test fails");
		}
	}

	@Test
	@DisplayName("without a transaction, each test gets an edition, and none is left, even after a test that fails")
	void without_transaction() {
		TestKit.run(WithoutTransactionSample.class).assertStatistics(stats -> stats.started(3).succeeded(2).failed(1));

		assertThat(TestKit.editionsLeft(pool)).isZero();
	}

	/** Stubs in @BeforeEach, inside the test transaction. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(Application.class)
	@EnablePlsqlMocks
	@Transactional
	static class BeforeEachSample {

		@Autowired PlsqlMocks mocks;
		@Autowired Freights freights;

		@BeforeEach
		void stub() {
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));
		}

		@Test
		void sees_the_stub() {
			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
		}
	}

	@Test
	@DisplayName("a stub made in @BeforeEach holds in the test")
	void stub_in_before_each() {
		TestKit.run(BeforeEachSample.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** The test's PlsqlMocks as a parameter of the test method, with @Autowired, as Spring asks. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(Application.class)
	@EnablePlsqlMocks
	static class ParameterSample {

		@Autowired Freights freights;

		@Test
		void takes_the_mocks(@Autowired PlsqlMocks mocks) {
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
		}
	}

	@Test
	@DisplayName("with Spring, a test method takes its PlsqlMocks as a parameter with @Autowired")
	void parameter_with_autowired() {
		TestKit.run(ParameterSample.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** The same parameter, without @Autowired. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(Application.class)
	@EnablePlsqlMocks
	static class ParameterWithoutAutowiredSample {

		@Test
		void takes_the_mocks(PlsqlMocks mocks) {
		}
	}

	@Test
	@DisplayName("with Spring, without @Autowired the parameter is not resolved: Spring resolves only those it marks")
	void parameter_without_autowired() {
		Throwable failure = TestKit.firstFailure(TestKit.run(ParameterWithoutAutowiredSample.class)
				.assertStatistics(stats -> stats.failed(1)));
		assertThat(failure).hasMessageContaining("No ParameterResolver registered for parameter");
	}

	/** A context of a class without the annotation. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(Application.class)
	static class NotAnnotatedSample {

		@Autowired DataSource dataSource;

		@Test
		void data_source_as_it_is() {
			assertThat(dataSource).isNotInstanceOf(EditionDataSource.class);
		}
	}

	@Test
	@DisplayName("a context of a class without @EnablePlsqlMocks keeps its DataSource as it is")
	void not_annotated() {
		TestKit.run(NotAnnotatedSample.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** The contexts the samples below ran in, to compare them. */
	static final java.util.Map<String, org.springframework.context.ApplicationContext> CONTEXTS =
			new java.util.concurrent.ConcurrentHashMap<>();

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(Application.class)
	@EnablePlsqlMocks
	static class FirstAnnotatedSample {
		@Test
		void records(@Autowired org.springframework.context.ApplicationContext context) {
			CONTEXTS.put("first", context);
		}
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(Application.class)
	@EnablePlsqlMocks
	static class SecondAnnotatedSample {
		@Test
		void records(@Autowired org.springframework.context.ApplicationContext context) {
			CONTEXTS.put("second", context);
		}
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(Application.class)
	static class NotAnnotatedContextSample {
		@Test
		void records(@Autowired org.springframework.context.ApplicationContext context) {
			CONTEXTS.put("not annotated", context);
		}
	}

	@Test
	@DisplayName("classes annotated alike share one cached context, which a class without the annotation does not use")
	void context_cache() {
		TestKit.run(FirstAnnotatedSample.class).assertStatistics(stats -> stats.succeeded(1));
		TestKit.run(SecondAnnotatedSample.class).assertStatistics(stats -> stats.succeeded(1));
		TestKit.run(NotAnnotatedContextSample.class).assertStatistics(stats -> stats.succeeded(1));

		assertThat(CONTEXTS.get("second")).isSameAs(CONTEXTS.get("first"));
		assertThat(CONTEXTS.get("not annotated")).isNotSameAs(CONTEXTS.get("first"));
	}

	/** Two DataSources, none primary. */
	@Configuration
	static class TwoDataSources {

		@Bean(destroyMethod = "")
		DataSource first() {
			return pool;
		}

		@Bean(destroyMethod = "")
		DataSource second() {
			return pool;
		}
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(TwoDataSources.class)
	@EnablePlsqlMocks
	static class TwoDataSourcesSample {

		@Test
		void starts() {
		}
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(TwoDataSources.class)
	@EnablePlsqlMocks(dataSource = "second")
	static class NamedDataSourceSample {

		@Autowired PlsqlMocks mocks;

		@Test
		void mocks_through_the_named_one() {
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

			assertThat(SpringPlsqlPackages.create(Freights.class, PlsqlMocks.wrap(pool)).calculate("SP", "CE"))
					.isEqualByComparingTo("24.90");
		}
	}

	@Test
	@DisplayName("several DataSources, none primary: refused until one is named, and then that one is used")
	void several_data_sources() {
		Throwable failure = TestKit.firstFailure(
				TestKit.run(TwoDataSourcesSample.class).assertStatistics(stats -> stats.failed(1)));
		assertThat(failure).hasStackTraceContaining("@EnablePlsqlMocks(dataSource");

		TestKit.run(NamedDataSourceSample.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** A primary one among several. */
	@Configuration
	static class PrimaryDataSource {

		@Bean(destroyMethod = "")
		@Primary
		DataSource main() {
			return pool;
		}

		@Bean(destroyMethod = "")
		DataSource reports() {
			return pool;
		}
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(PrimaryDataSource.class)
	@EnablePlsqlMocks
	static class PrimarySample {

		@Autowired PlsqlMocks mocks;

		@Test
		void mocks_through_the_primary_one() {
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

			assertThat(SpringPlsqlPackages.create(Freights.class, PlsqlMocks.wrap(pool)).calculate("SP", "CE"))
					.isEqualByComparingTo("24.90");
		}
	}

	@Test
	@DisplayName("several DataSources, one primary: the primary one is used")
	void primary_data_source() {
		TestKit.run(PrimarySample.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}
}
