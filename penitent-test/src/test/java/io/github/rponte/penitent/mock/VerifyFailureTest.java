package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.never;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.times;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.mock.StubCompositesTest.FreightItem;

/**
 * A failed {@code verify} shows the calls the routine actually got in the test (#148), in their order, each value in
 * the form the expected side shows it, so that {@code 'CE'} and {@code 'ce'} stand side by side.
 */
class VerifyFailureTest {

	@PlsqlPackage("FREIGHTS")
	interface Freights {
		BigDecimal calculate(String pOrigin, String pDestination);

		@PlsqlRoutine("CALCULATE")
		BigDecimal calculateByWeight(BigDecimal pWeight);

		String label(LocalDate pDay);

		String describeFlag(boolean pFlag);

		void quote(String pUf, PlsqlOut<BigDecimal> pValue);

		void register(FreightItem pItem);
	}

	@PlsqlPackage("FREIGHT_DOCS")
	interface Docs {
		String quoteOf(String pOrder);
	}

	@PlsqlPackage("FREIGHT_STAMPS")
	interface Stamps {
		void stamp(PlsqlOut<String> pText);
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Freights freights;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		freights = PlsqlPackages.create(Freights.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("nothing matched: the calls are listed in order, so that 'CE' stands beside 'ce'")
	void lists_the_calls() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculate(any(), any())).thenReturn(BigDecimal.ONE);
		freights.calculate("SP", "ce");
		freights.calculate("RJ", null);

		assertThatThrownBy(() -> verify(mock).calculate("SP", "CE"))
				.isInstanceOf(AssertionError.class)
				.hasMessage("PLSQL_TEST.FREIGHTS.CALCULATE('SP', 'CE') was expected 1 time in this test, and was called"
						+ " 0 times. The calls of PLSQL_TEST.FREIGHTS.CALCULATE in this test, in order:\n"
						+ "  1. ('SP', 'ce')\n"
						+ "  2. ('RJ', null)")
				// the testing page shows this message, as the library prints it
				.hasMessage(page("testing.md#testing/verify-failure"));
	}

	/** A block of the docs, by its {@code page#name}, as the page shows it. */
	private static String page(String ref) {
		String page = ref.substring(0, ref.indexOf('#'));
		String marker = "<!-- snippet: " + ref.substring(ref.indexOf('#') + 1) + " -->";
		try {
			List<String> lines = Files.readAllLines(Path.of("..", "docs", page), StandardCharsets.UTF_8);
			int start = lines.indexOf(marker) + 2;
			int end = lines.subList(start, lines.size()).indexOf("```") + start;
			return String.join("\n", lines.subList(start, end));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Test
	@DisplayName("a count that does not match shows the calls too, as does never()")
	void a_count_that_does_not_match() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculate(any(), any())).thenReturn(BigDecimal.ONE);
		freights.calculate("SP", "CE");
		freights.calculate("SP", "CE");

		assertThatThrownBy(() -> verify(mock).calculate("SP", "CE"))
				.hasMessage("PLSQL_TEST.FREIGHTS.CALCULATE('SP', 'CE') was expected 1 time in this test, and was called"
						+ " 2 times. The calls of PLSQL_TEST.FREIGHTS.CALCULATE in this test, in order:\n"
						+ "  1. ('SP', 'CE')\n"
						+ "  2. ('SP', 'CE')");
		assertThatThrownBy(() -> verify(mock, never()).calculate(eq("SP"), any()))
				.hasMessageEndingWith("in order:\n  1. ('SP', 'CE')\n  2. ('SP', 'CE')");
	}

	@Test
	@DisplayName("a routine not called at all says so, with no list")
	void not_called() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculate(any(), any())).thenReturn(BigDecimal.ONE);

		assertThatThrownBy(() -> verify(mock).calculate("SP", "CE"))
				.hasMessage("PLSQL_TEST.FREIGHTS.CALCULATE('SP', 'CE') was expected 1 time in this test, and was called"
						+ " 0 times. PLSQL_TEST.FREIGHTS.CALCULATE was not called at all in this test");
	}

	@Test
	@DisplayName("the first 10 calls, then how many more")
	void the_first_ten() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculate(any(), any())).thenReturn(BigDecimal.ONE);
		for (int i = 1; i <= 13; i++) {
			freights.calculate("SP", "D" + i);
		}

		assertThatThrownBy(() -> verify(mock).calculate("SP", "CE"))
				.hasMessageContaining("\n  10. ('SP', 'D10')\n  ... and 3 more")
				.hasMessageNotContaining("'D11'");
	}

	@Test
	@DisplayName("a value above 100 characters is cut, with its length")
	void a_long_value() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculate(any(), any())).thenReturn(BigDecimal.ONE);
		String hundred = "x".repeat(100);
		freights.calculate("SP", hundred + "yz");

		assertThatThrownBy(() -> verify(mock).calculate("SP", "CE"))
				.hasMessageEndingWith("  1. ('SP', '" + hundred + "'... (102 characters))");
	}

	@Test
	@DisplayName("each type in the form the expected side shows it: a number, a date as the ISO text recorded, a"
			+ " BOOLEAN, a pure OUT, an object type, which is not recorded")
	void each_type() {
		Freights mock = mocks.mock(Freights.class);
		when(mock.calculateByWeight(any())).thenReturn(BigDecimal.ONE);
		when(mock.label(any())).thenReturn("label");
		when(mock.describeFlag(PlsqlMocks.anyBoolean())).thenReturn("flag");
		doNothing().when(mock).quote(any(), out(BigDecimal.TEN));
		doNothing().when(mock).register(any());
		freights.calculateByWeight(new BigDecimal("24.90"));
		freights.label(LocalDate.of(2026, 11, 24));
		freights.describeFlag(true);
		freights.quote("SP", new PlsqlOut<>());
		freights.register(new FreightItem(1L, "SP"));

		assertThatThrownBy(() -> verify(mock).calculateByWeight(BigDecimal.ONE))
				.hasMessageEndingWith("in order:\n  1. (24.9)");
		assertThatThrownBy(() -> verify(mock).label(LocalDate.of(2026, 1, 1)))
				.hasMessageEndingWith("in order:\n  1. ('2026-11-24T00:00:00')");
		assertThatThrownBy(() -> verify(mock).describeFlag(false))
				.hasMessageEndingWith("in order:\n  1. (TRUE)");
		assertThatThrownBy(() -> verify(mock).quote("RJ", new PlsqlOut<>()))
				.hasMessageEndingWith("in order:\n  1. ('SP', (OUT))");
		assertThatThrownBy(() -> verify(mock, times(2)).register(any()))
				.hasMessageEndingWith("in order:\n  1. ((not recorded))");
	}

	@Test
	@DisplayName("an IN OUT shows the value that came in, and a JSON argument its document as text, apart from SQL NULL")
	void in_out_and_json() throws SQLException {
		Stamps stamps = mocks.mock(Stamps.class);
		doNothing().when(stamps).stamp(out("stamped"));
		PlsqlPackages.create(Stamps.class, dataSource).stamp(PlsqlOut.of("raw"));
		assertThatThrownBy(() -> verify(stamps).stamp(PlsqlOut.of("other")))
				.hasMessageEndingWith("in order:\n  1. ('raw')");

		try (Connection connection = TestDatabase.pool().getConnection()) {
			assumeTrue(connection.getMetaData().getDatabaseMajorVersion() >= 21, "the JSON type exists only from 21c on");
		}
		Docs docs = mocks.mock(Docs.class);
		when(docs.quoteOf(any())).thenReturn("{}");
		Docs real = PlsqlPackages.create(Docs.class, dataSource);
		real.quoteOf("{ \"a\" : 1 }");
		real.quoteOf("null");
		real.quoteOf(null);
		assertThatThrownBy(() -> verify(docs).quoteOf("{\"b\":2}"))
				.hasMessageEndingWith("in order:\n  1. ('{\"a\":1}')\n  2. ('null')\n  3. (null)");
	}
}
