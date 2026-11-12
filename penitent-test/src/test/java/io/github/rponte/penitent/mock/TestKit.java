package io.github.rponte.penitent.mock;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

/** Runs sample test classes through JUnit's own test kit, to check how each of their tests ended. */
final class TestKit {

	private static final String SAMPLES = "penitent.mock.samples";

	private TestKit() {
	}

	static Events run(Class<?> sample) {
		return EngineTestKit.engine("junit-jupiter").configurationParameter(SAMPLES, "true")
				.selectors(selectClass(sample)).execute().testEvents();
	}

	/** The failure of the first test that failed. */
	static Throwable firstFailure(Events events) {
		return events.failed().stream().findFirst().orElseThrow()
				.getPayload(TestExecutionResult.class).orElseThrow().getThrowable().orElseThrow();
	}

	static int editionsLeft(DataSource dataSource) {
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
	static class OnlyThroughTheTestKit implements ExecutionCondition {
		@Override
		public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
			return context.getConfigurationParameter(SAMPLES).isPresent() ? ConditionEvaluationResult.enabled("sample")
					: ConditionEvaluationResult.disabled("a sample, run only through the test kit");
		}
	}
}
