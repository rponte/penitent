package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;

/**
 * A null PL/SQL BOOLEAN that comes back, with driver 23 or later against a database before Oracle 23ai.
 *
 * <p>The driver sends the null of such a bind in a format of its own for an older database, and the
 * database answers with a one-byte value and the null indicator; the driver then decides by the length
 * alone, so the null reads as true or false, with nothing to tell it apart. The library cannot see the
 * indicator through JDBC, so it warns, once per method, and {@code docs/compatibility.md} lists the ways
 * around it. The first test pins the driver's behavior: if a new driver fixes it, it fails, and the
 * warning and the documentation can go.
 */
class NullBooleanOutputTest extends OracleIntegrationTest {

	private static final String WARNING = "a null BOOLEAN that comes back reads as true or false";

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Returned {
		@PlsqlRoutine("NEGATE")
		Boolean negate(Boolean value);
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface ReturnedTwice {
		@PlsqlRoutine("NEGATE")
		Boolean negate(Boolean value);
	}

	@PlsqlPackage("FIXTURE_OUT")
	interface Out {
		@PlsqlRoutine("INVERT_BOOLEAN")
		void invert(PlsqlOut<Boolean> value);
	}

	interface Blocks {
		@PlsqlBlock("begin :result := :value; end;")
		Boolean echo(Boolean value);

		@PlsqlBlock("declare l_value boolean := :value; begin :result := case when l_value then 'yes' when not l_value then 'no' end; end;")
		String describe(Boolean value);
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Elsewhere {
		@PlsqlRoutine("NEGATE")
		Boolean negate(Boolean value);
	}

	@Test
	@DisplayName("driver 23 against a database before 23ai: a null BOOLEAN that comes back is not null")
	void the_driver_misreads_the_null() {
		assumeTrue(nullBooleanOutputMisread(), "only driver 23 or later against a database before 23ai");

		assertThat(pkg(Returned.class).negate(null)).isNotNull();
	}

	@Test
	@DisplayName("it warns once per method: a return value, an OUT, and a block's :result")
	void warns_once_per_method() {
		assumeTrue(nullBooleanOutputMisread(), "only driver 23 or later against a database before 23ai");

		List<String> warnings = warnings(() -> {
			pkg(ReturnedTwice.class).negate(true);
			pkg(ReturnedTwice.class).negate(false);
			pkg(Out.class).invert(PlsqlOut.of(true));
			pkg(Blocks.class).echo(true);
		});

		assertThat(warnings).hasSize(3).allSatisfy(w -> assertThat(w)
				.contains(WARNING)
				.contains("driver 23")
				.contains("CASE WHEN flag THEN 'Y' WHEN NOT flag THEN 'N' END"));
		assertThat(warnings.get(0)).contains("PLSQL_TEST.FIXTURE_SCALARS.NEGATE returns a BOOLEAN");
		assertThat(warnings.get(1)).contains("PLSQL_TEST.FIXTURE_OUT.INVERT_BOOLEAN has a BOOLEAN that comes back");
		assertThat(warnings.get(2)).contains("Blocks.echo returns a BOOLEAN");
	}

	@Test
	@DisplayName("a BOOLEAN that only goes in does not warn")
	void boolean_in_does_not_warn() {
		assumeTrue(nullBooleanOutputMisread(), "only driver 23 or later against a database before 23ai");

		assertThat(warnings(() -> pkg(Blocks.class).describe(null))).isEmpty();
	}

	@Test
	@DisplayName("on any other combination of driver and database, nothing warns")
	void no_warning_elsewhere() {
		assumeFalse(nullBooleanOutputMisread(), "this combination warns: see the tests above");

		assertThat(warnings(() -> pkg(Elsewhere.class).negate(null))).isEmpty();
	}

	private static List<String> warnings(Runnable call) {
		Logger logger = (Logger) LoggerFactory.getLogger("io.github.rponte.penitent.call");
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		logger.addAppender(events);
		try {
			call.run();
		} finally {
			logger.detachAppender(events);
		}
		return events.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
				.toList();
	}
}
