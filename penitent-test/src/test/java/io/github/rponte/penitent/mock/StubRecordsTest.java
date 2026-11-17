package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * A stub answers with a RECORD of a package, a {@code %ROWTYPE} or an {@code INDEX BY}, which PL/SQL has no
 * constructor for: a RECORD is written as a qualified expression, a {@code %ROWTYPE} as the qualified expression of a
 * RECORD with its columns, which Oracle assigns to it, and an {@code INDEX BY} with named indexes. The real routines
 * fail, so a test passes only through the stub.
 */
class StubRecordsTest {

	/** QUOTE_T is (UF, VALUE): the Java fields come in the other order, which the RECORD's order must win over. */
	record Quote(BigDecimal value, String uf) {
	}

	/** LEG_T: a RECORD holding a RECORD. */
	record Leg(String origin, Quote quote) {
	}

	/** FREIGHT_ITEM%ROWTYPE. */
	record Item(Long id, String uf) {
	}

	/** FREIGHT_NOTE%ROWTYPE, whose column "Note Text" needs quotes. */
	record Note(Long id, @io.github.rponte.penitent.PlsqlAttr("Note Text") String noteText) {
	}

	@PlsqlPackage("FREIGHT_RECORDS")
	interface Records {
		Quote quoteOf(String pUf);

		void quote(String pUf, PlsqlOut<Quote> pQuote);

		Leg legOf(String pOrigin);

		List<Quote> quotesOf(String pUf);

		List<Quote> quotesByIndex(String pUf);

		List<String> ufs();

		List<String> ufsByKey();

		Item itemOf(BigDecimal pId);

		void loadItem(BigDecimal pId, PlsqlOut<Item> pItem);

		Note noteOf(BigDecimal pId);

		String describe(String pUf, BigDecimal pId);
	}

	/** SETTLE(P_UF, P_DAY, P_QUOTE OUT), bound by name, with the Java arguments in another order. */
	@PlsqlPackage(value = "FREIGHT_RECORDS", bind = PlsqlBind.BY_NAME)
	interface ByName {
		void settle(@io.github.rponte.penitent.PlsqlParam("P_QUOTE") PlsqlOut<Quote> quote,
				@io.github.rponte.penitent.PlsqlParam("P_DAY") LocalDate day,
				@io.github.rponte.penitent.PlsqlParam("P_UF") String uf);

		/** SETTLE_LATER(P_UF, P_DAY DEFAULT SYSDATE, P_QUOTE OUT), without P_DAY. */
		void settleLater(@io.github.rponte.penitent.PlsqlParam("P_QUOTE") PlsqlOut<Quote> quote,
				@io.github.rponte.penitent.PlsqlParam("P_UF") String uf);
	}

	private static final Quote CE = new Quote(new BigDecimal("24.9"), "CE");

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Records records;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		records = PlsqlPackages.create(Records.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a stub returns a RECORD of a package, in the RECORD's order whatever the Java fields' order")
	void record() {
		when(mocks.mock(Records.class).quoteOf("CE")).thenReturn(CE);

		assertThat(records.quoteOf("CE")).isEqualTo(CE);
	}

	@Test
	@DisplayName("a stub of a procedure sets an OUT of a RECORD")
	void record_out() {
		doNothing().when(mocks.mock(Records.class)).quote(eq("CE"), out(CE));

		PlsqlOut<Quote> quote = new PlsqlOut<>();
		records.quote("CE", quote);
		assertThat(quote.get()).isEqualTo(CE);
	}

	@Test
	@DisplayName("bound by name, the OUT of a RECORD goes to its parameter, whatever the order of the Java arguments")
	void record_out_by_name() {
		ByName byName = PlsqlPackages.create(ByName.class, dataSource);
		doNothing().when(mocks.mock(ByName.class)).settle(out(CE), any(), eq("CE"));

		PlsqlOut<Quote> quote = new PlsqlOut<>();
		byName.settle(quote, LocalDate.of(2026, 11, 17), "CE");
		assertThat(quote.get()).isEqualTo(CE);
	}

	@Test
	@DisplayName("bound by name, a stub of a routine whose parameter with a DEFAULT the Java method leaves out")
	void by_name_leaving_a_default_out() {
		ByName byName = PlsqlPackages.create(ByName.class, dataSource);
		doNothing().when(mocks.mock(ByName.class)).settleLater(out(CE), eq("CE"));

		PlsqlOut<Quote> quote = new PlsqlOut<>();
		byName.settleLater(quote, "CE");
		assertThat(quote.get()).isEqualTo(CE);
	}

	@Test
	@DisplayName("a stub returns a RECORD holding a RECORD")
	void record_in_a_record() {
		Leg leg = new Leg("SP", CE);
		when(mocks.mock(Records.class).legOf("SP")).thenReturn(leg);

		assertThat(records.legOf("SP")).isEqualTo(leg);
	}

	@Test
	@DisplayName("a stub returns a nested table of RECORDs, and an empty one")
	void collection_of_records() {
		Records mock = mocks.mock(Records.class);
		when(mock.quotesOf("CE")).thenReturn(List.of(CE, new Quote(BigDecimal.ONE, "SP")));
		when(mock.quotesOf("RJ")).thenReturn(List.of());

		assertThat(records.quotesOf("CE")).containsExactly(CE, new Quote(BigDecimal.ONE, "SP"));
		assertThat(records.quotesOf("RJ")).isEmpty();
	}

	@Test
	@DisplayName("a stub returns an INDEX BY of text and of RECORDs, indexed from 1, and an empty one")
	void index_by() {
		Records mock = mocks.mock(Records.class);
		when(mock.ufs()).thenReturn(List.of("SP", "CE"));
		when(mock.quotesByIndex("CE")).thenReturn(List.of(CE));
		when(mock.quotesByIndex("RJ")).thenReturn(List.of());

		assertThat(records.ufs()).containsExactly("SP", "CE");
		assertThat(records.quotesByIndex("CE")).containsExactly(CE);
		assertThat(records.quotesByIndex("RJ")).isEmpty();
	}

	@Test
	@DisplayName("an INDEX BY VARCHAR2 is refused by the core, as a call would be: JDBC does not carry its keys")
	void index_by_varchar2_refused() {
		Records mock = mocks.mock(Records.class);

		assertThatThrownBy(() -> when(mock.ufsByKey()).thenReturn(List.of("SP")))
				.isInstanceOf(io.github.rponte.penitent.exception.PlsqlMappingException.class)
				.hasMessageContaining("UFS_BY_KEY_T is INDEX BY VARCHAR2");
	}

	@Test
	@DisplayName("a stub returns a %ROWTYPE, and sets one as an OUT")
	void rowtype() {
		Records mock = mocks.mock(Records.class);
		when(mock.itemOf(BigDecimal.ONE)).thenReturn(new Item(1L, "SP"));
		doNothing().when(mock).loadItem(eq(BigDecimal.TEN), out(new Item(10L, "CE")));

		assertThat(records.itemOf(BigDecimal.ONE)).isEqualTo(new Item(1L, "SP"));
		PlsqlOut<Item> item = new PlsqlOut<>();
		records.loadItem(BigDecimal.TEN, item);
		assertThat(item.get()).isEqualTo(new Item(10L, "CE"));
	}

	@Test
	@DisplayName("a stub returns a %ROWTYPE of a table whose column needs quotes")
	void rowtype_with_a_quoted_column() {
		Note note = new Note(1L, "fragile");
		when(mocks.mock(Records.class).noteOf(BigDecimal.ONE)).thenReturn(note);

		assertThat(records.noteOf(BigDecimal.ONE)).isEqualTo(note);
	}

	@Test
	@DisplayName("stubs of %ROWTYPE records of two tables in one test: each table has its own LITERAL_T")
	void rowtypes_of_two_tables() {
		Records mock = mocks.mock(Records.class);
		Note note = new Note(2L, "fragile");
		when(mock.itemOf(BigDecimal.ONE)).thenReturn(new Item(1L, "SP"));
		when(mock.noteOf(BigDecimal.valueOf(2))).thenReturn(note);

		assertThat(records.itemOf(BigDecimal.ONE)).isEqualTo(new Item(1L, "SP"));
		assertThat(records.noteOf(BigDecimal.valueOf(2))).isEqualTo(note);
	}

	@Test
	@DisplayName("PL/SQL calling the routines gets the stubbed RECORD and %ROWTYPE")
	void called_from_plsql() {
		Records mock = mocks.mock(Records.class);
		when(mock.quoteOf("CE")).thenReturn(CE);
		when(mock.itemOf(BigDecimal.ONE)).thenReturn(new Item(1L, "SP"));

		assertThat(records.describe("CE", BigDecimal.ONE)).isEqualTo("CE=24.9, 1/SP");
	}
}
