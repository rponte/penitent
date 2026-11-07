package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/** The logger names are the contract: they are what library users turn on in their configuration. */
class CallLoggingTest extends OracleIntegrationTest {

	private static final String CALL = "io.github.rponte.penitent.call";
	private static final String BIND = "io.github.rponte.penitent.bind";

	record Address(String street, Integer num) {
	}

	record Customer(Long id, String fullName, String active, Integer vip, java.time.LocalDate birthday,
			String status, Address address, List<String> nicknames, List<Address> addresses) {
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Scalars {
		String echoText(String value);

		void raiseError(String message);
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		Address echoAddress(Address value);

		List<Address> echoAddresses(List<Address> value);

		List<String> echoNames(List<String> value);

		Customer echoCustomer(Customer value);
	}

	@PlsqlPackage("FIXTURE_LOG")
	interface WithOut {
		String compute(int a, PlsqlOut<Integer> doubled, PlsqlOut<Integer> accumulated);
	}

	@PlsqlPackage("FIXTURE_OUT")
	interface Outputs {
		void plus(int a, int b, PlsqlOut<Integer> total);
	}

	interface Blocks {
		@PlsqlBlock("BEGIN :result := UPPER(:text); END;")
		String uppercase(String text);
	}

	interface BlockLong {
		@PlsqlBlock("""
				BEGIN
				  :result := UPPER(:text);
				END;
				""")
		String uppercase(String text);
	}

	private final ListAppender<ILoggingEvent> events = new ListAppender<>();

	/** The levels of logback-test.xml, which turns the loggers on for the whole build. */
	private final java.util.Map<String, Level> configured = new java.util.HashMap<>();

	@BeforeEach
	void capture() {
		events.start();
		for (String name : List.of(CALL, BIND)) {
			configured.put(name, logger(name).getLevel());
			logger(name).addAppender(events);
			// only in the list: on the console, the log of these tests would pollute the build
			logger(name).setAdditive(false);
			// each test turns on what it checks
			logger(name).setLevel(Level.OFF);
		}
	}

	@AfterEach
	void restore() {
		for (String name : List.of(CALL, BIND)) {
			logger(name).detachAppender(events);
			logger(name).setLevel(configured.get(name));
			logger(name).setAdditive(true);
		}
	}

	private static Logger logger(String name) {
		return (Logger) LoggerFactory.getLogger(name);
	}

	private static void enable(String name, Level level) {
		logger(name).setLevel(level);
	}

	private List<String> lines() {
		return events.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
	}

	private String lineStartingWith(String prefix) {
		return lines().stream().filter(l -> l.startsWith(prefix)).findFirst()
				.orElseThrow(() -> new AssertionError("no line starts with '" + prefix + "' in " + lines()));
	}

	@Test
	@DisplayName("off by default: nothing is logged without configuration")
	void off_by_default() {
		// without a level of their own, the loggers inherit the root's, which an application leaves at INFO or WARN
		logger(CALL).setLevel(null);
		logger(BIND).setLevel(null);

		pkg(Scalars.class).echoText("a");

		assertThat(events.list).isEmpty();
	}

	@Test
	@DisplayName("at debug: the routine, the method, the SQL, the data dictionary and the time, without the values")
	void call() {
		enable(CALL, Level.DEBUG);

		pkg(Scalars.class).echoText("secret");

		assertThat(lineStartingWith("DEBUG Read dictionary"))
				.contains("PLSQL_TEST.FIXTURE_SCALARS.ECHO_TEXT in").endsWith(" ms");
		assertThat(lineStartingWith("DEBUG Calling routine"))
				.isEqualTo("DEBUG Calling routine     : PLSQL_TEST.FIXTURE_SCALARS.ECHO_TEXT <- Scalars.echoText");
		assertThat(lineStartingWith("DEBUG -> SQL"))
				.isEqualTo("DEBUG -> SQL              : {? = call PLSQL_TEST.FIXTURE_SCALARS.ECHO_TEXT(?)}");
		assertThat(lineStartingWith("DEBUG <- completed")).matches("DEBUG <- completed        : \\d+ ms");
		assertThat(lines()).noneMatch(l -> l.contains("secret"));
	}

	@Test
	@DisplayName("the data dictionary is read only on the first call")
	void dictionary_once() {
		enable(CALL, Level.DEBUG);
		Scalars scalars = pkg(Scalars.class);

		scalars.echoText("a");
		scalars.echoText("b");

		assertThat(lines()).filteredOn(l -> l.startsWith("DEBUG Read dictionary")).hasSize(1);
		assertThat(lines()).filteredOn(l -> l.startsWith("DEBUG Calling routine")).hasSize(2);
	}

	@Test
	@DisplayName("at trace, on the other logger: the values sent and the return value")
	void values() {
		enable(BIND, Level.TRACE);

		pkg(Scalars.class).echoText("it's");

		assertThat(lines()).containsExactly(
				"TRACE -> with bind values : P_VALUE='it''s'",
				"TRACE <- result           : 'it''s'");
	}

	@Test
	@DisplayName("OBJECT is logged as sent to the database, with the type name, and a collection by its elements")
	void composites() {
		enable(BIND, Level.TRACE);
		Composites composites = pkg(Composites.class);

		composites.echoAddress(new Address("Street A", null));
		composites.echoNames(List.of("a", "b", "c", "d", "e", "f", "g"));

		assertThat(lines().get(0)).isEqualTo("TRACE -> with bind values : P_VALUE=PLSQL_TEST.ADDRESS_T('Street A', NULL)");
		assertThat(lines().get(1)).isEqualTo("TRACE <- result           : PLSQL_TEST.ADDRESS_T('Street A', NULL)");
		assertThat(lines().subList(2, 10)).containsExactly(
				"TRACE -> with bind values : P_VALUE=[",
				"TRACE                         'a',",
				"TRACE                         'b',",
				"TRACE                         'c',",
				"TRACE                         'd',",
				"TRACE                         'e',",
				"TRACE                         ... (7 elements)",
				"TRACE                       ]");
	}

	@Test
	@DisplayName("a collection inside an OBJECT gets one more indent, and a single-element one stays on the line")
	void nested_collection() {
		enable(BIND, Level.TRACE);

		pkg(Composites.class).echoCustomer(new Customer(7L, "Ann", null, null, null, null, null, List.of("nests"),
				List.of(new Address("Street A", 1), new Address("Street B", 2))));

		assertThat(lines().subList(0, 4)).containsExactly(
				"TRACE -> with bind values : P_VALUE=PLSQL_TEST.CUSTOMER_T(7, 'Ann', NULL, NULL, NULL, NULL, NULL, ['nests'], [",
				"TRACE                         PLSQL_TEST.ADDRESS_T('Street A', 1),",
				"TRACE                         PLSQL_TEST.ADDRESS_T('Street B', 2)",
				"TRACE                       ])");
	}

	@Test
	@DisplayName("function with OUT and IN OUT: the OUTs on one line, before the return value")
	void function_with_out_and_in_out() {
		enable(BIND, Level.TRACE);
		PlsqlOut<Integer> doubled = new PlsqlOut<>();

		pkg(WithOut.class).compute(5, doubled, PlsqlOut.of(10));

		assertThat(lines()).containsExactly(
				"TRACE -> with bind values : P_A=5, P_ACCUMULATED=10",
				"TRACE <- OUT values       : P_DOUBLED=10, P_ACCUMULATED=15",
				"TRACE <- result           : 'ok'");
	}

	@Test
	@DisplayName("a line break in a value is logged as \\n, and does not create a new log line")
	void line_break_in_value() {
		enable(BIND, Level.TRACE);

		pkg(Scalars.class).echoText("line 1\nline 2\r\nline 3");

		assertThat(lines()).containsExactly(
				"TRACE -> with bind values : P_VALUE='line 1\\nline 2\\r\\nline 3'",
				"TRACE <- result           : 'line 1\\nline 2\\r\\nline 3'");
	}

	@Test
	@DisplayName("collection of OBJECT, in both directions, and a null collection")
	void collection_of_object() {
		enable(BIND, Level.TRACE);
		Composites composites = pkg(Composites.class);

		composites.echoAddresses(List.of(new Address("Street A", 1)));
		composites.echoAddresses(null);

		assertThat(lines()).containsExactly(
				"TRACE -> with bind values : P_VALUE=[PLSQL_TEST.ADDRESS_T('Street A', 1)]",
				"TRACE <- result           : [PLSQL_TEST.ADDRESS_T('Street A', 1)]",
				"TRACE -> with bind values : P_VALUE=NULL",
				"TRACE <- result           : NULL");
	}

	@Test
	@DisplayName("long text is truncated, with its length")
	void long_text() {
		enable(BIND, Level.TRACE);

		pkg(Scalars.class).echoText("x".repeat(3000));

		assertThat(lines().get(0)).endsWith("x... (3000 chars)'").hasSizeLessThan(2100);
	}

	@Test
	@DisplayName("OUT is logged with the parameter name")
	void out() {
		enable(BIND, Level.TRACE);

		pkg(Outputs.class).plus(2, 3, new PlsqlOut<>());

		assertThat(lineStartingWith("TRACE -> with bind values")).endsWith("=2, P_B=3")
				.doesNotContain("P_TOTAL");
		assertThat(lineStartingWith("TRACE <- OUT values       :")).endsWith("=5");
	}

	@Test
	@DisplayName("anonymous block: the method, the block text and the values by name")
	void block() {
		enable(CALL, Level.DEBUG);
		enable(BIND, Level.TRACE);

		pkg(Blocks.class).uppercase("abc");

		assertThat(lines()).containsExactly(
				"DEBUG Calling block       : Blocks.uppercase",
				"DEBUG -> SQL              : BEGIN ? := UPPER(?); END;",
				"TRACE -> with bind values : text='abc'",
				"TRACE <- result           : 'ABC'",
				lineStartingWith("DEBUG <- completed"));
	}

	@Test
	@DisplayName("multi-line block: one block line per log record, aligned")
	void multi_line_block() {
		enable(CALL, Level.DEBUG);

		pkg(BlockLong.class).uppercase("abc");

		// the continuation aligns with the value: the label has 19 characters, plus " : "
		String continuation = "DEBUG " + " ".repeat(22);
		assertThat(lines().subList(1, 5)).containsExactly(
				"DEBUG -> SQL              : BEGIN",
				continuation + "  ? := UPPER(?);",
				continuation + "END;",
				lineStartingWith("DEBUG <- completed"));
	}

	@Test
	@DisplayName("failure: one line with the database error, no stack trace, and the exception still propagates")
	void failure() {
		enable(CALL, Level.DEBUG);

		assertThatThrownBy(() -> pkg(Scalars.class).raiseError("rule violated"))
				.isInstanceOf(PlsqlRoutineRaisedException.class);

		String failure = lineStartingWith("DEBUG <- failed");
		assertThat(failure).matches("DEBUG <- failed           : \\d+ ms: SQLException: ORA-20100: rule violated.*");
		assertThat(events.list).allMatch(e -> e.getThrowableProxy() == null);
		assertThat(lines()).noneMatch(l -> l.startsWith("DEBUG <- completed"));
	}
}
