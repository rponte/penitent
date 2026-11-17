package io.github.rponte.penitent.mock;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestExecutionResult.Status;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.Events;
import org.opentest4j.TestAbortedException;

/** Runs sample test classes through JUnit's own test kit, to check how each of their tests ended. */
public final class TestKit {

	private static final String SAMPLES = "penitent.mock.samples";

	private TestKit() {
	}

	public static Events run(Class<?> sample) {
		return run(sample, System.err);
	}

	/** Runs the sample and shows, on the log given, the exception of each of its tests or containers that failed. */
	static Events run(Class<?> sample, PrintStream log) {
		return execute(sample, log).testEvents();
	}

	/** The failure of the first container, a class, that failed: as a {@code @BeforeAll} that throws makes it. */
	public static Throwable containerFailure(Class<?> sample) {
		return firstFailure(execute(sample, System.err).containerEvents());
	}

	/**
	 * Runs the sample, and shows the exception of each test or container that failed, or that aborted for anything but
	 * an assumption: a test that only counts how the samples ended would otherwise hide why one of them failed.
	 */
	private static EngineExecutionResults execute(Class<?> sample, PrintStream log) {
		EngineExecutionResults results = EngineTestKit.engine("junit-jupiter").configurationParameter(SAMPLES, "true")
				.selectors(selectClass(sample)).execute();
		results.allEvents().finished().stream().forEach(event -> show(event, log));
		return results;
	}

	private static void show(Event event, PrintStream log) {
		TestExecutionResult result = event.getPayload(TestExecutionResult.class).orElseThrow();
		Throwable throwable = result.getThrowable().orElse(null);
		if (throwable == null || result.getStatus() == Status.SUCCESSFUL
				|| throwable instanceof TestAbortedException) {
			return;
		}
		synchronized (log) {
			log.println("[TestKit] sample " + result.getStatus() + ": " + event.getTestDescriptor().getDisplayName()
					+ " " + event.getTestDescriptor().getUniqueId());
			throwable.printStackTrace(log);
		}
	}

	/** The failure of the first test that failed. */
	public static Throwable firstFailure(Events events) {
		return events.failed().stream().findFirst().orElseThrow()
				.getPayload(TestExecutionResult.class).orElseThrow().getThrowable().orElseThrow();
	}

	public static int editionsLeft(DataSource dataSource) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM ALL_EDITIONS"
						+ " WHERE EDITION_NAME LIKE 'PENITENT\\_MOCK\\_%' ESCAPE '\\'")) {
			result.next();
			return result.getInt(1);
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Runs a sample class only through the test kit: the build would otherwise run it as a test of its own. */
	public static class OnlyThroughTheTestKit implements ExecutionCondition {
		@Override
		public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
			return context.getConfigurationParameter(SAMPLES).isPresent() ? ConditionEvaluationResult.enabled("sample")
					: ConditionEvaluationResult.disabled("a sample, run only through the test kit");
		}
	}
}
