package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.Events;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * The extension, through sample test classes run with JUnit's own test kit: each test of a sample gets an edition,
 * stubs of one test never reach the next, and the edition is gone after each test, passed or failed.
 */
class PlsqlMockExtensionTest {

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		BigDecimal calculate(String pOrigin, String pDestination);
	}

	private static DataSource pool;
	static DataSource dataSource;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		dataSource = PlsqlMocks.wrap(pool);
	}

	private static final String SAMPLES = "penitent.mock.samples";

	private static Events run(Class<?> sample) {
		return EngineTestKit.engine("junit-jupiter").configurationParameter(SAMPLES, "true")
				.selectors(selectClass(sample)).execute().testEvents();
	}

	/** Runs a sample class only through the test kit: the build would otherwise run it as a test of its own. */
	static class OnlyThroughTheTestKit implements ExecutionCondition {
		@Override
		public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
			return context.getConfigurationParameter(SAMPLES).isPresent() ? ConditionEvaluationResult.enabled("sample")
					: ConditionEvaluationResult.disabled("a sample, run only through the test kit");
		}
	}

	private static int editionsLeft() {
		try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ALL_EDITIONS"
						+ " WHERE EDITION_NAME LIKE 'PENITENT\\_MOCK\\_%' ESCAPE '\\'")) {
			result.next();
			return result.getInt(1);
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Two tests: the first stubs, the second sees the real routine. */
	@ExtendWith(OnlyThroughTheTestKit.class)
	@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
	static class StubsOfOneTestOnly {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		final Freights freights = PlsqlPackages.create(Freights.class, dataSource);

		@Test
		@Order(1)
		void stubs() {
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
		}

		@Test
		@Order(2)
		void sees_the_real_routine() {
			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("75");
		}
	}

	@Test
	@DisplayName("each test gets an edition of its own: a stub of one test never reaches the next")
	void stubs_of_one_test_only() {
		run(StubsOfOneTestOnly.class).assertStatistics(stats -> stats.started(2).succeeded(2));
		assertThat(editionsLeft()).isZero();
	}

	/** A test that fails after stubbing. */
	@ExtendWith(OnlyThroughTheTestKit.class)
	static class FailingSample {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		@Test
		void fails() {
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(BigDecimal.ONE);
			throw new AssertionError("the test fails");
		}
	}

	@Test
	@DisplayName("the edition is dropped after a test that fails, too")
	void failing_test() {
		run(FailingSample.class).assertStatistics(stats -> stats.started(1).failed(1));
		assertThat(editionsLeft()).isZero();
	}

	/** Stubs in @BeforeEach, and takes the test's PlsqlMocks as a parameter. */
	@ExtendWith(OnlyThroughTheTestKit.class)
	static class StubsInBeforeEach {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		Freights mock;

		@BeforeEach
		void stub() {
			mock = mocks.mock(Freights.class);
			when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));
		}

		@Test
		void takes_the_mocks_as_a_parameter(PlsqlMocks testMocks) {
			Freights other = testMocks.mock(Freights.class);
			when(other.calculate("RJ", "BA")).thenReturn(new BigDecimal("31"));

			Freights freights = PlsqlPackages.create(Freights.class, dataSource);
			assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
			assertThat(freights.calculate("RJ", "BA")).isEqualByComparingTo("31");
			verify(mock).calculate("SP", "CE");
		}
	}

	@Test
	@DisplayName("a stub made in @BeforeEach holds in the test, and the test can take its PlsqlMocks as a parameter")
	void stubs_in_before_each() {
		run(StubsInBeforeEach.class).assertStatistics(stats -> stats.started(1).succeeded(1));
		assertThat(editionsLeft()).isZero();
	}

	/** Uses the extension's mock outside a test: in a static initializer, before any test began. */
	@ExtendWith(OnlyThroughTheTestKit.class)
	static class MockOutsideATestSample {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		@Test
		void mocks_outside() {
			PlsqlMockExtension unregistered = PlsqlMockExtension.on(() -> dataSource);
			unregistered.mock(Freights.class);
		}
	}

	@Test
	@DisplayName("mock outside a running test is refused with a clear message")
	void mock_outside_a_test() {
		Events events = run(MockOutsideATestSample.class);
		events.assertStatistics(stats -> stats.started(1).failed(1));
		Throwable failure = events.failed().stream().findFirst().orElseThrow()
				.getPayload(TestExecutionResult.class).orElseThrow().getThrowable().orElseThrow();
		assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("no test is running");
	}
}
