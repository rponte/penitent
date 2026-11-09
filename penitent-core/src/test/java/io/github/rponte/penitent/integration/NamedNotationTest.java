package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlParam;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

class NamedNotationTest extends OracleIntegrationTest {

	@PlsqlPackage(value = "FIXTURE_NAMED", bind = PlsqlBind.BY_NAME)
	interface ByName {
		/** Omits P_B, which has a DEFAULT and is in the middle of the list. */
		int f(int pA, int pC);

		@PlsqlRoutine("F")
		int full(int pA, int pB, int pC);

		@PlsqlRoutine("F")
		int outOfOrder(int pC, int pA);

		@PlsqlRoutine("F")
		int withAnnotation(@PlsqlParam("P_A") int first, @PlsqlParam("p_c") int last);

		void p(int pA, PlsqlOut<Integer> pDoubled, PlsqlOut<Integer> pAccumulated);

		/** Two parameters of the same type: the Java order does not matter. */
		String period(LocalDate pEnd, LocalDate pStart);

		/** The version of G is chosen by the parameter name. */
		@PlsqlRoutine("G")
		String g(int pY);

		int noArguments();

		String concatNames(String pPrefix, List<String> pNames, String pSuffix);
	}

	@PlsqlPackage(value = "FIXTURE_NAMED", bind = PlsqlBind.BY_NAME)
	interface Unpaired {
		@PlsqlRoutine("F")
		int f(int pA, int pZ);
	}

	@PlsqlPackage(value = "FIXTURE_NAMED", bind = PlsqlBind.BY_NAME)
	interface MissingRequired {
		@PlsqlRoutine("F")
		int f(int pA);
	}

	@PlsqlPackage(value = "FIXTURE_NAMED", bind = PlsqlBind.BY_NAME)
	interface Duplicated {
		@PlsqlRoutine("F")
		int f(int pA, @PlsqlParam("P_A") int other, int pC);
	}

	@PlsqlPackage(value = "FIXTURE_NAMED", bind = PlsqlBind.BY_NAME)
	interface UnpairedOverload {
		@PlsqlRoutine("G")
		String g(int pZ);
	}

	/** The default is still by position: the same swapped order results in swapped values. */
	@PlsqlPackage("FIXTURE_NAMED")
	interface ByPosition {
		String period(LocalDate pEnd, LocalDate pStart);

		int f(int pA, int pB, int pC);
	}

	private static final LocalDate STARTS = LocalDate.parse("2018-01-01");
	private static final LocalDate ENDS = LocalDate.parse("2018-01-31");

	private final ByName byName = pkg(ByName.class);

	@Test
	@DisplayName("omits a DEFAULT in the middle of the list")
	void default_in_the_middle() {
		assertThat(byName.f(1, 3)).isEqualTo(173);
		assertThat(byName.full(1, 2, 3)).isEqualTo(123);
	}

	@Test
	@DisplayName("the Java order does not matter, and the name can come from @PlsqlParam, in any case")
	void order_and_annotation() {
		assertThat(byName.outOfOrder(3, 1)).isEqualTo(173);
		assertThat(byName.withAnnotation(1, 3)).isEqualTo(173);
	}

	@Test
	@DisplayName("two parameters of the same type in a different order: by name gets it right, by position silently swaps them")
	void protects_against_swapped_positions() {
		assertThat(byName.period(ENDS, STARTS)).isEqualTo("2018-01-01..2018-01-31");
		assertThat(pkg(ByPosition.class).period(ENDS, STARTS)).isEqualTo("2018-01-31..2018-01-01");
	}

	@Test
	@DisplayName("OUT and IN OUT by name")
	void out_and_in_out() {
		PlsqlOut<Integer> doubled = new PlsqlOut<>();
		PlsqlOut<Integer> accumulated = PlsqlOut.of(10);

		byName.p(5, doubled, accumulated);

		assertThat(doubled.get()).isEqualTo(10);
		assertThat(accumulated.get()).isEqualTo(15);
	}

	@Test
	@DisplayName("an overload that differs only by parameter name is chosen by name")
	void overload_by_name() {
		assertThat(byName.g(1)).isEqualTo("y");
	}

	@Test
	@DisplayName("routine without arguments")
	void without_arguments() {
		assertThat(byName.noArguments()).isEqualTo(42);
	}

	@Test
	@DisplayName("Java parameter without a match: the error says the name looked for, the ones that exist and the way out")
	void no_match() {
		assertThatThrownBy(() -> pkg(Unpaired.class).f(1, 3))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("parameter pZ looks for P_Z")
				.hasMessageContaining("P_A NUMBER")
				.hasMessageContaining("@PlsqlParam");
	}

	@Test
	@DisplayName("an Oracle parameter without DEFAULT and without a match is an error")
	void missing_required() {
		assertThatThrownBy(() -> pkg(MissingRequired.class).f(1))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("requires [P_C]")
				.hasMessageContaining("DEFAULT");
	}

	@Test
	@DisplayName("two Java parameters for the same Oracle parameter is an error")
	void duplicate() {
		assertThatThrownBy(() -> pkg(Duplicated.class).f(1, 2, 3))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("pA and other both map to P_A");
	}

	@Test
	@DisplayName("overload with no version that has that name: the error says what each version rejected")
	void overload_without_match() {
		assertThatThrownBy(() -> pkg(UnpairedOverload.class).g(1))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("g(int): no version of PLSQL_TEST.FIXTURE_NAMED.G matches by name")
				.hasMessageContaining("P_X NUMBER")
				.hasMessageContaining("P_Y NUMBER");
	}

	@Test
	@DisplayName("the SQL in the log shows the names, in the Java method's order, and with two it stays on one line")
	void log() {
		List<String> lines = capturing(() -> pkg(ByName.class).outOfOrder(3, 1));

		assertThat(lines).filteredOn(l -> l.startsWith("DEBUG -> SQL")).containsExactly(
				"DEBUG -> SQL              : {? = call PLSQL_TEST.FIXTURE_NAMED.F(P_C => ?, P_A => ?)}");
		assertThat(lines).filteredOn(l -> l.startsWith("TRACE")).containsExactly(
				"TRACE -> with bind values : P_C=3, P_A=1",
				"TRACE <- result           : 173");
	}

	@Test
	@DisplayName("from 3 parameters on, SQL and values are printed one per line, with the names aligned")
	void log_one_per_line() {
		List<String> lines = capturing(() -> pkg(ByName.class).full(1, 2, 3));

		String debug = "DEBUG " + " ".repeat(22);
		String trace = "TRACE " + " ".repeat(22);
		// after "Read dictionary" and "Calling routine", and before "<- completed"
		assertThat(lines.subList(2, lines.size() - 1)).containsExactly(
				"DEBUG -> SQL              : {? = call PLSQL_TEST.FIXTURE_NAMED.F(",
				debug + "  P_A => ?,",
				debug + "  P_B => ?,",
				debug + "  P_C => ?",
				debug + ")}",
				"TRACE -> with bind values : 3 parameters",
				trace + "  P_A = 1,",
				trace + "  P_B = 2,",
				trace + "  P_C = 3",
				"TRACE <- result           : 123");
	}

	@Test
	@DisplayName("by position, the SQL stays on one line, and the values break the same way")
	void log_by_position() {
		List<String> lines = capturing(() -> pkg(ByPosition.class).f(1, 2, 3));

		assertThat(lines).filteredOn(l -> l.startsWith("DEBUG -> SQL")).containsExactly(
				"DEBUG -> SQL              : {? = call PLSQL_TEST.FIXTURE_NAMED.F(?, ?, ?)}");
		assertThat(lines).contains("TRACE -> with bind values : 3 parameters");
	}

	@Test
	@DisplayName("a multi-line value, such as a collection, gets the parameter's indentation")
	void log_collection_between_parameters() {

		List<String> lines = capturing(() -> pkg(ByName.class).concatNames("<", List.of("a", "b"), ">"));

		String trace = "TRACE " + " ".repeat(22);
		assertThat(lines).filteredOn(l -> l.startsWith("TRACE")).containsExactly(
				"TRACE -> with bind values : 3 parameters",
				trace + "  P_PREFIX = '<',",
				trace + "  P_NAMES  = [",
				trace + "    'a',",
				trace + "    'b'",
				trace + "  ],",
				trace + "  P_SUFFIX = '>'",
				"TRACE <- result           : '<ab>'");
	}

	private static List<String> capturing(Runnable call) {
		List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger("io.github.rponte.penitent.call"),
				(Logger) LoggerFactory.getLogger("io.github.rponte.penitent.bind"));
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		List<Level> configured = loggers.stream().map(Logger::getLevel).toList();
		for (Logger logger : loggers) {
			logger.addAppender(events);
			logger.setAdditive(false);
		}
		loggers.get(0).setLevel(Level.DEBUG);
		loggers.get(1).setLevel(Level.TRACE);
		try {
			call.run();
		} finally {
			for (int i = 0; i < loggers.size(); i++) {
				loggers.get(i).detachAppender(events);
				loggers.get(i).setLevel(configured.get(i));
				loggers.get(i).setAdditive(true);
			}
		}
		return events.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
	}
}
