package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlType;

/**
 * The log of the mocks, under {@code io.github.rponte.penitent.mock}: the edition of each test at DEBUG, each stub and
 * each verify at DEBUG, the PL/SQL generated at TRACE, what is out of the ordinary at WARN, and nothing at INFO.
 */
class MockLoggingTest {

	private static final String MOCK = "io.github.rponte.penitent.mock";

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		BigDecimal calculate(String pOrigin, String pDestination);

		String describe(FreightItem pRow);

		String describeRegion(Region pRegion);
	}

	/** The same routine, through a block that writes the table's name as people do, in lowercase. */
	interface Blocks {
		@PlsqlBlock("BEGIN :result := freights.describe(:row); END;")
		String describe(@PlsqlType("freight_item%ROWTYPE") FreightItem row);
	}

	/** A row of FREIGHT_ITEM. */
	record FreightItem(Long id, String uf) {
	}

	/** A row of PLSQL_REGISTRY.REGION. */
	record Region(Long id, String uf) {
	}

	private static DataSource pool;
	private static DataSource dataSource;

	private final ListAppender<ILoggingEvent> events = new ListAppender<>();
	private Level configured;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		dataSource = PlsqlMocks.wrap(pool);
	}

	private static Logger logger() {
		return (Logger) LoggerFactory.getLogger(MOCK);
	}

	@BeforeEach
	void capture() {
		configured = logger().getLevel();
		events.start();
		logger().addAppender(events);
	}

	@AfterEach
	void release() {
		logger().detachAppender(events);
		logger().setLevel(configured);
	}

	private List<String> lines() {
		return events.list.stream().map(e -> e.getLevel() + " " + e.getFormattedMessage()).toList();
	}

	@Test
	@DisplayName("at DEBUG: the edition, each stub and each verify, with their times")
	void debug() {
		logger().setLevel(Level.DEBUG);
		Freights freights = PlsqlPackages.create(Freights.class, dataSource);
		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
			Freights mock = mocks.mock(Freights.class);
			when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));
			freights.calculate("SP", "CE");
			verify(mock).calculate("SP", "CE");
		}

		assertThat(lines()).hasSize(4);
		assertThat(lines().get(0)).matches("DEBUG Began mocks         : edition PENITENT_MOCK_\\w+ in \\d+ ms");
		assertThat(lines().get(1)).matches(
				"DEBUG Stubbed routine     : PLSQL_TEST\\.FREIGHTS\\.CALCULATE\\('SP', 'CE'\\) -> RETURN 24\\.90 in \\d+ ms");
		assertThat(lines().get(2)).isEqualTo(
				"DEBUG Verified routine    : PLSQL_TEST.FREIGHTS.CALCULATE('SP', 'CE') called 1 time, as expected");
		assertThat(lines().get(3)).matches("DEBUG Ended mocks         : edition PENITENT_MOCK_\\w+ dropped in \\d+ ms");
	}

	@Test
	@DisplayName("at TRACE: the PL/SQL generated for each stub, too")
	void trace() {
		logger().setLevel(Level.TRACE);
		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenThrow(-20200, "no freight");
		}

		assertThat(lines()).anySatisfy(line -> assertThat(line)
				.startsWith("TRACE -> generated PL/SQL : FUNCTION calculate(")
				.contains("RAISE_APPLICATION_ERROR(-20200, 'no freight');")
				.contains("END calculate;"));
		assertThat(lines()).anySatisfy(line -> assertThat(line).matches(
				"DEBUG Stubbed routine     : PLSQL_TEST\\.FREIGHTS\\.CALCULATE\\('SP', 'CE'\\)"
						+ " -> RAISE_APPLICATION_ERROR\\(-20200, 'no freight'\\) in \\d+ ms"));
	}

	@Test
	@DisplayName("at DEBUG: the subtype a %ROWTYPE record goes by in the edition, once per test, and its DDL at TRACE;"
			+ " the call log shows the name each call binds")
	void row_type() {
		logger().setLevel(Level.TRACE);
		Logger calls = (Logger) LoggerFactory.getLogger("io.github.rponte.penitent.call");
		Level callsConfigured = calls.getLevel();
		calls.setLevel(Level.DEBUG);
		calls.addAppender(events);
		try {
			Freights freights = PlsqlPackages.create(Freights.class, dataSource);
			try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
				mocks.begin();
				freights.describe(new FreightItem(1L, "SP"));
				freights.describe(new FreightItem(2L, "CE"));
				freights.calculate("SP", "CE");
			}
		} finally {
			calls.detachAppender(events);
			calls.setLevel(callsConfigured);
		}

		String alias = "PLSQL_TEST\\.PENITENT_ROW_\\w+\\.ROW_T";
		assertThat(lines()).filteredOn(line -> line.contains("Aliased row type")).singleElement().asString()
				.matches("DEBUG Aliased row type    : \"PLSQL_TEST\"\\.FREIGHT_ITEM%ROWTYPE as " + alias + " in \\d+ ms");
		assertThat(lines()).anySatisfy(line -> assertThat(line).matches("(?s)TRACE -> generated PL/SQL : CREATE OR"
				+ " REPLACE PACKAGE \"PLSQL_TEST\"\\.PENITENT_ROW_\\w+ AS\n +SUBTYPE ROW_T IS \"PLSQL_TEST\"\\.FREIGHT_ITEM%ROWTYPE;"
				+ "\n +END PENITENT_ROW_\\w+;"));
		assertThat(lines()).filteredOn(line -> line.contains("-> type name")).hasSize(2).allSatisfy(line -> assertThat(line)
				.matches("DEBUG -> type name        : \"PLSQL_TEST\"\\.FREIGHT_ITEM%ROWTYPE as " + alias));
	}

	@Test
	@DisplayName("each table gets a package of its own, numbered in the order the test binds them, created once")
	void row_type_per_table() {
		logger().setLevel(Level.DEBUG);
		Freights freights = PlsqlPackages.create(Freights.class, dataSource);
		Blocks blocks = PlsqlPackages.create(Blocks.class, dataSource);
		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
			assertThat(freights.describe(new FreightItem(1L, "SP"))).isEqualTo("1/SP");
			assertThat(freights.describeRegion(new Region(2L, "CE"))).isEqualTo("2/CE");
			assertThat(blocks.describe(new FreightItem(3L, "RJ"))).isEqualTo("3/RJ");
			assertThat(freights.describeRegion(new Region(4L, "BA"))).isEqualTo("4/BA");
		}

		assertThat(lines()).filteredOn(line -> line.contains("Aliased row type")).satisfiesExactly(
				line -> assertThat(line).matches("DEBUG Aliased row type    : \"PLSQL_TEST\"\\.FREIGHT_ITEM%ROWTYPE as"
						+ " PLSQL_TEST\\.PENITENT_ROW_\\w{12}_1\\.ROW_T in \\d+ ms"),
				line -> assertThat(line).matches("DEBUG Aliased row type    : \"PLSQL_REGISTRY\"\\.REGION%ROWTYPE as"
						+ " PLSQL_TEST\\.PENITENT_ROW_\\w{12}_2\\.ROW_T in \\d+ ms"));
	}

	@Test
	@DisplayName("at WARN: an edition left by a run that died, when the next begin drops it")
	void leftover() {
		logger().setLevel(Level.WARN);
		TestDatabase.execute(pool, "CREATE EDITION PENITENT_MOCK_DIED AS CHILD OF ORA$BASE");

		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
		}

		assertThat(lines()).containsExactly(
				"WARN Dropped edition     : PENITENT_MOCK_DIED, left by a run that died before ending its mocks");
	}

	@Test
	@DisplayName("at INFO, the default for most applications: nothing")
	void info() {
		logger().setLevel(Level.INFO);
		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
			when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(BigDecimal.ONE);
		}

		assertThat(lines()).isEmpty();
	}
}
