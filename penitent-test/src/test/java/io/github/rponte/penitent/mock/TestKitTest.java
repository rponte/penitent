package io.github.rponte.penitent.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.testkit.engine.EngineTestKit;

/**
 * The test kit shows the exception of each sample that fails, with its stack: a test that only counts how the samples
 * ended would otherwise hide why one of them failed.
 */
class TestKitTest {

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class Failing {
		@Test
		void fails() {
			throw new IllegalStateException("the cause of the failure");
		}
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class FailingBeforeAll {
		@BeforeAll
		static void prepare() {
			throw new IllegalStateException("the cause of the container failure");
		}

		@Test
		void neverRuns() {
		}
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class Passing {
		@Test
		void passes() {
		}
	}

	@Test
	void shows_the_exception_of_a_test_that_fails() {
		ByteArrayOutputStream log = new ByteArrayOutputStream();

		TestKit.run(Failing.class, new PrintStream(log, true, StandardCharsets.UTF_8))
				.assertStatistics(stats -> stats.started(1).failed(1));

		assertThat(log.toString(StandardCharsets.UTF_8)).contains("fails()").contains(Failing.class.getName())
				.contains("java.lang.IllegalStateException: the cause of the failure")
				.contains("at " + Failing.class.getName() + ".fails(");
	}

	@Test
	void shows_the_exception_of_a_container_that_fails() {
		ByteArrayOutputStream log = new ByteArrayOutputStream();

		TestKit.run(FailingBeforeAll.class, new PrintStream(log, true, StandardCharsets.UTF_8))
				.assertStatistics(stats -> stats.started(0));

		assertThat(log.toString(StandardCharsets.UTF_8))
				.contains("java.lang.IllegalStateException: the cause of the container failure");
	}

	@Test
	void shows_nothing_when_the_samples_pass() {
		ByteArrayOutputStream log = new ByteArrayOutputStream();

		TestKit.run(Passing.class, new PrintStream(log, true, StandardCharsets.UTF_8))
				.assertStatistics(stats -> stats.started(1).succeeded(1));

		assertThat(log.toString(StandardCharsets.UTF_8)).isEmpty();
	}

	/** Why {@code sample} is skipped when it runs on its own, as an IDE runs it, without the test kit's parameter. */
	private static String skippedReason(String sample) {
		return EngineTestKit.engine("junit-jupiter").selectors(DiscoverySelectors.selectClass(sample)).execute()
				.containerEvents().skipped().list().get(0).getPayload(String.class).orElseThrow();
	}

	@Test
	void a_nested_sample_skipped_on_its_own_names_the_test_that_runs_it() {
		assertThat(skippedReason(Passing.class.getName())).contains("TestKitTest").contains("-Dpenitent.mock.samples=true");
	}

	@Test
	void a_sample_of_its_own_skipped_on_its_own_names_the_test_given_by_its_annotation() {
		// a docs example without Spring, which one execution lacks; not public, so selected by its name
		assertThat(skippedReason("io.github.rponte.penitent.mock.docs.CheckoutTest")).contains("DocsTestingTest");
	}
}
