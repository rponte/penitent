package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * Oracle's {@code JSON} type, from 21c on, as a {@code Map<String, Object>} for an object, a {@code List<Object>} for
 * an array, and a number or a {@code Boolean} for a scalar at the top. The library parses and writes the text with the
 * driver's own JSON API, {@code oracle.sql.json}, so the same mapping works with driver 19, whose call converts the
 * text in the database. Every number inside is a {@code BigDecimal}, as Oracle's JSON type stores it.
 */
class JsonMapTest extends OracleIntegrationTest {

	private static final int DRIVER_MAJOR_VERSION = new oracle.jdbc.OracleDriver().getMajorVersion();

	@PlsqlPackage("FIXTURE_JSON")
	interface Documents {
		@PlsqlRoutine("ECHO")
		Map<String, Object> echoMap(Map<String, Object> pDoc);

		@PlsqlRoutine("ECHO")
		List<Object> echoList(List<Object> pDoc);

		@PlsqlRoutine("ECHO")
		Map<String, Object> parse(String pDoc);

		@PlsqlRoutine("ECHO")
		String echoText(String pDoc);

		@PlsqlRoutine("ECHO")
		String text(Map<String, Object> pDoc);

		@PlsqlRoutine("ECHO")
		List<Object> asList(String pDoc);

		@PlsqlRoutine("ECHO")
		Long echoLong(Long pDoc);

		@PlsqlRoutine("ECHO")
		Boolean echoBoolean(Boolean pDoc);

		@PlsqlRoutine("SCALAR")
		BigDecimal scalarAsBigDecimal();

		@PlsqlRoutine("SCALAR")
		int scalarAsInt();

		@PlsqlRoutine("SCALAR")
		Map<String, Object> scalarAsMap();

		@PlsqlRoutine("NULL_DOC")
		Map<String, Object> nullDoc();

		@PlsqlRoutine("BEYOND_NUMBER")
		String beyondNumberText();

		@PlsqlRoutine("BEYOND_NUMBER")
		Map<String, Object> beyondNumber();

		@PlsqlRoutine("BIG")
		Map<String, Object> big(BigDecimal pSize);
	}

	@PlsqlPackage("FIXTURE_JSON")
	interface Outputs {
		void outDoc(PlsqlOut<Map<String, Object>> pDoc);

		void wrap(PlsqlOut<Map<String, Object>> pDoc);
	}

	@PlsqlPackage("FIXTURE_JSON")
	interface Rows {
		List<Row> rows();

		@PlsqlRoutine("BEYOND_NUMBER_ROWS")
		List<TextRow> beyondNumberRows();

		@PlsqlRoutine("BEYOND_NUMBER_ROWS")
		List<Row> beyondNumberMapRows();
	}

	record TextRow(BigDecimal id, String doc) {
	}

	record Row(BigDecimal id, Map<String, Object> doc) {
	}

	@PlsqlPackage("FIXTURE_JSON")
	interface NarrowRows {
		List<NarrowRow> rows();
	}

	record NarrowRow(BigDecimal id, Map<String, String> doc) {
	}

	// snippet: types/json-map
	@PlsqlPackage("FIXTURE_JSON")
	interface Orders {
		Map<String, Object> echo(Map<String, Object> pDoc);   // an object

		@PlsqlRoutine("ECHO")
		List<Object> echoArray(List<Object> pDoc);            // an array

		BigDecimal scalar();                                  // a number at the top
	}
	// end-snippet

	@PlsqlPackage("FIXTURE_JSON")
	interface WrongValueType {
		@PlsqlRoutine("ECHO")
		Map<String, Integer> echo(Map<String, Integer> pDoc);
	}

	@PlsqlPackage("FIXTURE_JSON")
	interface WrongKeyType {
		@PlsqlRoutine("ECHO")
		String echo(Map<Integer, Object> pDoc);
	}

	@PlsqlPackage("FIXTURE_JSON")
	interface WrongElementType {
		@PlsqlRoutine("ECHO")
		List<String> echo(String pDoc);
	}

	@PlsqlPackage("FIXTURE_JSON")
	interface WrongShape {
		@PlsqlRoutine("ECHO")
		java.util.HashMap<String, Object> echo(String pDoc);
	}

	@BeforeEach
	void needsJson() {
		assumeTrue(DATABASE_MAJOR_VERSION >= 21, "the JSON type exists only from Oracle 21c on");
	}

	private Documents documents() {
		return pkg(Documents.class);
	}

	private static Map<String, Object> order() {
		Map<String, Object> customer = new LinkedHashMap<>();
		customer.put("name", "Ana \"Bia\" Ção");
		customer.put("since", 2019);
		Map<String, Object> order = new LinkedHashMap<>();
		order.put("id", 7L);
		order.put("total", new BigDecimal("24.90"));
		order.put("rate", 0.5d);
		order.put("paid", true);
		order.put("note", null);
		order.put("items", List.of("pen", "ink"));
		order.put("customer", customer);
		return order;
	}

	@Test
	@DisplayName("a Map both ways: the keys in their order, nested objects and arrays, true, null, and every number a"
			+ " BigDecimal with the value Oracle keeps, not its written form")
	void map_both_ways() {
		Map<String, Object> back = documents().echoMap(order());

		assertThat(back).isInstanceOf(LinkedHashMap.class);
		assertThat(back.keySet()).containsExactly("id", "total", "rate", "paid", "note", "items", "customer");
		assertThat(back.get("id")).isEqualTo(new BigDecimal("7"));
		assertThat(back.get("total")).as("24.90 kept as its value, 24.9").isEqualTo(new BigDecimal("24.9"));
		assertThat(back.get("rate")).isEqualTo(new BigDecimal("0.5"));
		assertThat(back.get("paid")).isEqualTo(Boolean.TRUE);
		assertThat(back).containsEntry("note", null);
		assertThat(back.get("items")).isEqualTo(List.of("pen", "ink"));
		assertThat(back.get("customer")).isEqualTo(
				new LinkedHashMap<>(Map.of("name", "Ana \"Bia\" Ção", "since", new BigDecimal("2019"))));
	}

	@Test
	@DisplayName("a List both ways, for a document whose top is an array")
	void list_both_ways() {
		List<Object> back = documents().echoList(Arrays.asList(1, "a", false, null, List.of(), Map.of()));

		assertThat(back).isInstanceOf(ArrayList.class)
				.containsExactly(new BigDecimal("1"), "a", false, null, List.of(), Map.of());
	}

	@Test
	@DisplayName("the text and the Map mix: a String goes as the JSON text, and a Map's text comes back as Oracle writes it")
	void text_and_map_mix() {
		assertThat(documents().parse("{ \"a\" : [1, 2.50] }"))
				.isEqualTo(Map.of("a", List.of(new BigDecimal("1"), new BigDecimal("2.5"))));
		assertThat(documents().text(order())).isEqualTo("{\"id\":7,\"total\":24.9,\"rate\":0.5,\"paid\":true,"
				+ "\"note\":null,\"items\":[\"pen\",\"ink\"],\"customer\":{\"name\":\"Ana \\\"Bia\\\" Ção\",\"since\":2019}}");
	}

	@Test
	@DisplayName("a scalar at the top: a number, exactly, and true or false")
	void scalar_at_the_top() {
		assertThat(documents().scalarAsBigDecimal()).isEqualByComparingTo("42");
		assertThat(documents().scalarAsInt()).isEqualTo(42);
		assertThat(documents().echoLong(9_007_199_254_740_993L)).as("beyond a double's precision")
				.isEqualTo(9_007_199_254_740_993L);
		assertThat(documents().echoBoolean(true)).isTrue();
		assertThat(documents().echoBoolean(false)).isFalse();
	}

	@Test
	@DisplayName("null both ways: a null Map goes as SQL NULL, and SQL NULL comes back as null")
	void nulls() {
		assertThat(documents().echoMap(null)).isNull();
		assertThat(documents().nullDoc()).isNull();
		assertThat(documents().echoLong(null)).isNull();
	}

	@Test
	@DisplayName("a document above 32 KB, read into a Map")
	void large_document() {
		assertThat(documents().big(BigDecimal.valueOf(100_000)).get("text")).asString().hasSize(100_000);
	}

	@Test
	@DisplayName("a document that does not fit the declared type is refused, naming what it is and what to declare")
	void shape_mismatch_refused() {
		assertThatThrownBy(() -> documents().asList("{\"a\":1}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the return value of PLSQL_TEST.FIXTURE_JSON.ECHO: the document is a JSON object, which"
						+ " List does not hold: declare Map<String, Object>, or String for its text");
		assertThatThrownBy(() -> documents().scalarAsMap())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the return value of PLSQL_TEST.FIXTURE_JSON.SCALAR: the document is a JSON number, which"
						+ " Map does not hold: declare BigDecimal or another numeric type, or String for its text");
		assertThatThrownBy(() -> documents().parse("\"abc\""))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the document is a JSON string, which Map does not hold: declare String for its text");
	}

	@Test
	@DisplayName("a value JSON has no form for, inside a Map, is refused before the call, naming where it is")
	void unsupported_value_refused() {
		Map<String, Object> doc = new LinkedHashMap<>();
		doc.put("items", List.of(Map.of("when", LocalDate.of(2026, 11, 19))));
		assertThatThrownBy(() -> documents().echoMap(doc))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_DOC of PLSQL_TEST.FIXTURE_JSON.ECHO: $.items[0].when is a java.time.LocalDate,"
						+ " which JSON has no form for: put a String, a Number, a Boolean, a Map or a List there, or"
						+ " use a converter");
		Map<String, Object> notFinite = new LinkedHashMap<>();
		notFinite.put("x", Double.NaN);
		assertThatThrownBy(() -> documents().echoMap(notFinite))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("$.x is NaN, which JSON has no form for");
	}

	@Test
	@DisplayName("a Map or a List declared with other type arguments is refused before the call: the document's values"
			+ " are of many types")
	void type_arguments_refused() {
		assertThatThrownBy(() -> pkg(WrongValueType.class).echo(Map.of()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("is Oracle's JSON type, declared as java.util.Map<java.lang.String,"
						+ " java.lang.Integer>: declare Map<String, Object> for an object");
		assertThatThrownBy(() -> pkg(WrongKeyType.class).echo(Map.of()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare Map<String, Object> for an object");
		assertThatThrownBy(() -> pkg(WrongElementType.class).echo("[]"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare List<Object> for an array");
		assertThatThrownBy(() -> pkg(WrongShape.class).echo("{}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("HashMap");
	}

	@Test
	@DisplayName("a number beyond the range of Oracle's NUMBER, such as 1e200, which the database's parser takes but the"
			+ " driver's does not, is refused with a clear message: on the way in with drivers 21 and later, which parse"
			+ " the text before sending it, and on the way back with driver 19 on 23ai, whose call converts it in the"
			+ " database, where a String still takes its text. On 21c, the database's parser refuses it too, with"
			+ " ORA-01426")
	void number_out_of_range() {
		String expected = DRIVER_MAJOR_VERSION >= 21
				? "P_DOC is Oracle's JSON type, and the driver, which parses the text before sending it, does not take a"
						+ " number in it beyond the range of Oracle's NUMBER, about 1e-130 to 1e126: keep such a number as"
						+ " a JSON string"
				: "the return value of PLSQL_TEST.FIXTURE_JSON.ECHO: the document holds a number that the driver's JSON"
						+ " parser does not read, beyond the range of Oracle's NUMBER, about 1e-130 to 1e126: declare"
						+ " String for its text";
		if (DRIVER_MAJOR_VERSION < 21 && DATABASE_MAJOR_VERSION < 23) {
			// with driver 19 the database parses the text, and 21c's parser refuses the number itself
			assertThatThrownBy(() -> documents().echoText("{\"big\":1e200}")).hasMessageContaining("ORA-01426");
			assertThatThrownBy(() -> documents().parse("{\"big\":1e200}")).hasMessageContaining("ORA-01426");
			assertThatThrownBy(() -> documents().echoMap(Map.of("big", new BigDecimal("1e200"))))
					.hasMessageContaining("ORA-01426");
			return;
		}
		if (DRIVER_MAJOR_VERSION < 21) {
			// with driver 19 on 23ai, the database takes it, and the text comes back: no parser of the driver is involved
			assertThat(documents().echoText("{\"big\":1e200}")).isEqualTo("{\"big\":1E+200}");
		} else {
			assertThatThrownBy(() -> documents().echoText("{\"big\":1e200}"))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining(expected);
		}
		assertThatThrownBy(() -> documents().parse("{\"big\":1e200}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(expected);
		assertThatThrownBy(() -> documents().echoMap(Map.of("big", new BigDecimal("1e200"))))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(expected);
	}

	@Test
	@DisplayName("a number beyond NUMBER's range built in the database, which 23ai keeps as a double: its text comes back,"
			+ " written by the driver with drivers 21 and later and by the database with driver 19, and a Map refuses it;"
			+ " 21c refuses to build it, with ORA-01426")
	void number_out_of_range_from_the_database() {
		if (DATABASE_MAJOR_VERSION < 23) {
			assertThatThrownBy(() -> documents().beyondNumberText()).as("21c refuses such a number itself")
					.hasMessageContaining("ORA-01426: numeric overflow");
			return;
		}
		assertThat(documents().beyondNumberText())
				.isEqualTo(DRIVER_MAJOR_VERSION >= 21 ? "{\"big\":1.0E200}" : "{\"big\":1E+200}");
		assertThatThrownBy(() -> documents().beyondNumber())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the return value of PLSQL_TEST.FIXTURE_JSON.BEYOND_NUMBER: the document holds a"
						+ " number that the driver's JSON parser does not read");
		if (DRIVER_MAJOR_VERSION >= 21) {
			assertThat(pkg(Rows.class).beyondNumberRows())
					.containsExactly(new TextRow(new BigDecimal("1"), "{\"big\":1.0E200}"));
			assertThatThrownBy(() -> pkg(Rows.class).beyondNumberMapRows())
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("cursor column DOC: the document holds a number that the driver's JSON parser"
							+ " does not read");
		}
	}

	@Test
	@DisplayName("an OUT and an IN OUT as a Map, with driver 21 or later")
	void out_and_in_out() {
		assumeTrue(DRIVER_MAJOR_VERSION >= 21, "driver 19 cannot bring back an OUT of the JSON type");
		PlsqlOut<Map<String, Object>> out = new PlsqlOut<>();
		pkg(Outputs.class).outDoc(out);
		assertThat(out.get()).isEqualTo(Map.of("out", true));

		PlsqlOut<Map<String, Object>> inOut = PlsqlOut.of(new LinkedHashMap<>(Map.of("a", 1)));
		pkg(Outputs.class).wrap(inOut);
		assertThat(inOut.get()).isEqualTo(Map.of("wrapped", Map.of("a", new BigDecimal("1"))));
	}

	@Test
	@DisplayName("a cursor's JSON column into a record's Map, with driver 21 or later; a Map with other type arguments"
			+ " is refused")
	void cursor_column() {
		assumeTrue(DRIVER_MAJOR_VERSION >= 21, "driver 19 cannot read a JSON column of a cursor");
		assertThat(pkg(Rows.class).rows()).containsExactly(
				new Row(new BigDecimal("1"), Map.of("name", "one")),
				new Row(new BigDecimal("2"), null));
		assertThatThrownBy(() -> pkg(NarrowRows.class).rows())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NarrowRow.doc: cursor column DOC is Oracle's JSON type, declared as"
						+ " java.util.Map<java.lang.String, java.lang.String>: declare Map<String, Object> for an object");
	}

	@Test
	@DisplayName("the example of the Types page")
	void documented_example() {
		Orders orders = pkg(Orders.class);
		// snippet: types/json-map-use
		Map<String, Object> order = new LinkedHashMap<>();
		order.put("id", 7);
		order.put("total", new BigDecimal("24.90"));
		order.put("items", List.of("pen", "ink"));

		Map<String, Object> back = orders.echo(order);
		Object id = back.get("id");         // BigDecimal 7
		Object total = back.get("total");   // BigDecimal 24.9: the value, not its written form
		Object items = back.get("items");   // List ["pen", "ink"]
		// end-snippet
		assertThat(id).isEqualTo(new BigDecimal("7"));
		assertThat(total).isEqualTo(new BigDecimal("24.9"));
		assertThat(items).isEqualTo(List.of("pen", "ink"));
		assertThat(orders.echoArray(List.of(1, "a"))).containsExactly(new BigDecimal("1"), "a");
		assertThat(orders.scalar()).isEqualByComparingTo("42");
	}
}
