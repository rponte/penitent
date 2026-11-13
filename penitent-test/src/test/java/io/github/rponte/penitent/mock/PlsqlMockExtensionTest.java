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
import org.junit.jupiter.api.extension.RegisterExtension;
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

	private static Events run(Class<?> sample) {
		return TestKit.run(sample);
	}

	private static int editionsLeft() {
		return TestKit.editionsLeft(pool);
	}

	/** Two tests: the first stubs, the second sees the real routine. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
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
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
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
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
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

	/** The test's PlsqlMocks as a parameter of @BeforeEach and @AfterEach, around a test that uses it. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class ParametersAroundTheTestSample {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		static boolean afterEachSawIt;

		@BeforeEach
		void stub(PlsqlMocks testMocks) {
			when(testMocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));
		}

		@Test
		void sees_the_stub() {
			assertThat(PlsqlPackages.create(Freights.class, dataSource).calculate("SP", "CE"))
					.isEqualByComparingTo("24.90");
		}

		@org.junit.jupiter.api.AfterEach
		void verifies(PlsqlMocks testMocks) {
			verify(testMocks.mock(Freights.class), PlsqlMocks.never()).calculate("RJ", "BA");
			afterEachSawIt = true;
		}
	}

	@Test
	@DisplayName("the test's PlsqlMocks as a parameter of @BeforeEach and @AfterEach")
	void parameters_around_the_test() {
		run(ParametersAroundTheTestSample.class).assertStatistics(stats -> stats.started(1).succeeded(1));
		assertThat(ParametersAroundTheTestSample.afterEachSawIt).isTrue();
	}

	/** Asks for the test's PlsqlMocks in @BeforeAll, before any test runs. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class ParameterInBeforeAllSample {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		@org.junit.jupiter.api.BeforeAll
		static void tooEarly(PlsqlMocks testMocks) {
		}

		@Test
		void never_runs() {
		}
	}

	@Test
	@DisplayName("the test's PlsqlMocks as a parameter of @BeforeAll is refused: no test is running yet")
	void parameter_in_before_all() {
		Throwable failure = TestKit.containerFailure(ParameterInBeforeAllSample.class);
		assertThat(failure).hasStackTraceContaining("no test is running");
	}

	/** Uses the extension's mock outside a test: in a static initializer, before any test began. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
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
		Throwable failure = TestKit.firstFailure(events);
		assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining("no test is running");
	}
}
