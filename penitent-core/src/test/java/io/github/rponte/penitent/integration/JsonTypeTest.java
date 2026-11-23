package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Oracle's {@code JSON} type, from 21c on, mapped as {@code String}: the JSON text. The database stores it in a binary
 * form, so the text that comes back is the database's, with its own spacing and number forms. These tests need a
 * database of 21c or later; the ones named {@code driver_19_*} cover driver 19, which does not know the type, and
 * the rest need driver 21 or later. {@link JsonMapTest} covers the type as a {@code Map}, a {@code List} or a scalar.
 */
class JsonTypeTest extends OracleIntegrationTest {

	private static final int DRIVER_MAJOR_VERSION = new oracle.jdbc.OracleDriver().getMajorVersion();

	@PlsqlPackage("FIXTURE_JSON")
	interface Json {
		String echo(String pDoc);

		BigDecimal lengthOf(String pDoc);

		String nullDoc();

		void outDoc(PlsqlOut<String> pDoc);

		void wrap(PlsqlOut<String> pDoc);

		String big(BigDecimal pSize);

		String scalar();

		List<Row> rows();

		String kind(String pValue);
	}

	public record Order(String id, BigDecimal total, List<String> items) {
	}

	// snippet: types/json-converter
	public static class OrderJson implements PlsqlConverter<Order, String> {

		private static final JsonMapper JSON = JsonMapper.builder().build();

		@Override
		public String toDatabase(Order order) {
			return order == null ? null : JSON.writeValueAsString(order);
		}

		@Override
		public Order fromDatabase(String json) {
			return json == null ? null : JSON.readValue(json, Order.class);
		}
	}
	// end-snippet

	@PlsqlPackage("FIXTURE_JSON")
	interface Orders {
		// snippet: types/json-converter-use
		@PlsqlRoutine("ECHO")
		@PlsqlConvert(OrderJson.class)
		Order echo(@PlsqlConvert(OrderJson.class) Order pDoc);
		// end-snippet
	}

	// snippet: types/json-block
	interface JsonThroughBlocks {

		// IN: the text becomes JSON in the database, with JSON(...)
		@PlsqlBlock("begin :result := fixture_json.length_of(JSON(:doc)); end;")
		BigDecimal lengthOf(String doc);

		// return value: the JSON becomes text in the database, with JSON_SERIALIZE
		@PlsqlBlock("begin :result := JSON_SERIALIZE(fixture_json.echo(JSON(:doc)) RETURNING CLOB); end;")
		String echo(String doc);

		// OUT: into a JSON variable of the block, then as text
		@PlsqlBlock("""
				declare
				    v_doc JSON;
				begin
				    fixture_json.out_doc(v_doc);
				    :result := JSON_SERIALIZE(v_doc RETURNING CLOB);
				end;
				""")
		String outDoc();
	}
	// end-snippet

	@PlsqlPackage(value = "FIXTURE_JSON", bind = PlsqlBind.BY_NAME)
	interface JsonByName {
		String echo(String pDoc);
	}

	record Row(BigDecimal id, String doc) {
	}

	@PlsqlPackage("FIXTURE_JSON")
	interface Refused {
		List<String> docs();

		Row recordOf();

		Row rowOf();
	}

	@BeforeEach
	void needsJson() {
		assumeTrue(DATABASE_MAJOR_VERSION >= 21, "the JSON type exists only from Oracle 21c on");
	}

	private Json json() {
		assumeTrue(DRIVER_MAJOR_VERSION >= 21, "drivers before 21 do not know the JSON type");
		return pkg(Json.class);
	}

	@Test
	@DisplayName("IN and return value: the text goes as JSON and comes back in the database's own form")
	void in_and_return() {
		assertThat(json().echo("{ \"b\" : 2 }")).isEqualTo("{\"b\":2}");
		assertThat(json().lengthOf("{\"a\":1}")).isEqualByComparingTo("7");
	}

	@Test
	@DisplayName("a number keeps its value, not its written form: the JSON type stores it as a NUMBER")
	void numbers_keep_their_value_not_their_form() {
		assertThat(json().echo("{\"price\":24.90,\"n\":1.5e3}")).isEqualTo("{\"price\":24.9,\"n\":1500}");
	}

	@Test
	@DisplayName("null both ways")
	void nulls() {
		assertThat(json().lengthOf(null)).isNull();
		assertThat(json().nullDoc()).isNull();
		assertThat(json().echo(null)).isNull();
	}

	@Test
	@DisplayName("OUT and IN OUT")
	void out_and_in_out() {
		PlsqlOut<String> out = new PlsqlOut<>();
		json().outDoc(out);
		assertThat(out.get()).isEqualTo("{\"out\":true}");

		PlsqlOut<String> inOut = PlsqlOut.of("{\"a\":1}");
		json().wrap(inOut);
		assertThat(inOut.get()).isEqualTo("{\"wrapped\":{\"a\":1}}");
	}

	@Test
	@DisplayName("a document above 32 KB, both ways")
	void large_document() {
		String back = json().big(BigDecimal.valueOf(100_000));
		assertThat(back).hasSize(100_000 + "{\"text\":\"\"}".length()).startsWith("{\"text\":\"xxx");

		String sent = "{\"text\":\"" + "y".repeat(100_000) + "\"}";
		assertThat(json().lengthOf(sent)).isEqualByComparingTo(String.valueOf(sent.length()));
	}

	@Test
	@DisplayName("a scalar at the top of the document, as its JSON text")
	void scalar_at_the_top() {
		assertThat(json().scalar()).isEqualTo("42");
	}

	@Test
	@DisplayName("a record through the JSON text, with a converter of the application's own, here with Jackson")
	void converter_with_jackson() {
		assumeTrue(DRIVER_MAJOR_VERSION >= 21, "drivers before 21 do not know the JSON type");
		Order order = new Order("A-1", new BigDecimal("25.5"), List.of("pen", "ink"));
		assertThat(pkg(Orders.class).echo(order)).isEqualTo(order);
	}

	@Test
	@DisplayName("a JSON column of a cursor, null included")
	void cursor_column() {
		assertThat(json().rows()).containsExactly(new Row(BigDecimal.ONE, "{\"name\":\"one\"}"),
				new Row(BigDecimal.valueOf(2), null));
	}

	@Test
	@DisplayName("binding by name")
	void by_name() {
		assumeTrue(DRIVER_MAJOR_VERSION >= 21, "drivers before 21 do not know the JSON type");
		assertThat(pkg(JsonByName.class).echo("{\"a\":1}")).isEqualTo("{\"a\":1}");
	}

	@Test
	@DisplayName("text that is not JSON is an execution failure: the database's ORA-26327 with driver 23, the driver's"
			+ " own ORA-17001 with driver 21, which parses the text before sending it")
	void not_json() {
		Json json = json();
		assertThatThrownBy(() -> json.echo("not json"))
				.isInstanceOfSatisfying(io.github.rponte.penitent.exception.PlsqlExecutionException.class,
						e -> assertThat(e.getCode()).isEqualTo(DRIVER_MAJOR_VERSION >= 23 ? 26327 : 17001));
	}

	@Test
	@DisplayName("a collection of JSON, which the drivers cannot describe, is refused before the call")
	void collection_refused() {
		assertThatThrownBy(() -> pkg(Refused.class).docs())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("a collection of JSON");
	}

	@Test
	@DisplayName("a RECORD with a JSON field, and a %ROWTYPE of a table with a JSON column, which the drivers cannot"
			+ " describe, are refused before the call")
	void composites_with_json_refused() {
		assumeTrue(DRIVER_MAJOR_VERSION >= 21, "drivers before 21 do not know the JSON type");
		assertThatThrownBy(() -> pkg(Refused.class).recordOf())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("has a JSON field [DOC], which the Oracle driver cannot describe (ORA-17060)");
		assertThatThrownBy(() -> pkg(Refused.class).rowOf())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("has a JSON column [DOC], which the Oracle driver cannot describe (ORA-17060)")
				.hasMessageContaining("read the rows through a cursor");
	}

	@Test
	@DisplayName("overloads that take JSON and VARCHAR2 are both fit for a String: refused as ambiguous")
	void overload_with_varchar2_is_ambiguous() {
		Json json = json();
		assertThatThrownBy(() -> json.kind("{}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("more than one version of PLSQL_TEST.FIXTURE_JSON.KIND matches the method");
	}

	@Test
	@DisplayName("through anonymous blocks that convert in the database, with any driver, driver 19 included")
	void through_blocks_with_any_driver() {
		JsonThroughBlocks blocks = pkg(JsonThroughBlocks.class);
		assertThat(blocks.lengthOf("{\"a\":1}")).isEqualByComparingTo("7");
		assertThat(blocks.echo("{ \"b\" : 2 }")).isEqualTo("{\"b\":2}");
		assertThat(blocks.outDoc()).isEqualTo("{\"out\":true}");
	}

	@Test
	@DisplayName("through a block, a large document goes in, but a :result above 32,767 bytes fails: the block registers it"
			+ " as a VARCHAR2")
	void through_blocks_result_up_to_32k() {
		String large = "{\"text\":\"" + "z".repeat(50_000) + "\"}";
		JsonThroughBlocks blocks = pkg(JsonThroughBlocks.class);
		assertThat(blocks.lengthOf(large)).isEqualByComparingTo(String.valueOf(large.length()));
		assertThatThrownBy(() -> blocks.echo(large)).hasMessageContaining("ORA-06502");
	}

	private Json jsonWithDriver19() {
		assumeTrue(DRIVER_MAJOR_VERSION < 21, "a driver before 21, which does not know the JSON type");
		return pkg(Json.class);
	}

	@Test
	@DisplayName("driver 19: an IN and a return value, converted in the database, with JSON(?) and JSON_SERIALIZE")
	void driver_19_in_and_return() {
		Json json = jsonWithDriver19();
		assertThat(json.echo("{ \"b\" : 2 }")).isEqualTo("{\"b\":2}");
		assertThat(json.lengthOf("{\"a\":1}")).isEqualByComparingTo("7");
		assertThat(json.scalar()).isEqualTo("42");
	}

	@Test
	@DisplayName("driver 19: null both ways")
	void driver_19_nulls() {
		Json json = jsonWithDriver19();
		assertThat(json.lengthOf(null)).isNull();
		assertThat(json.nullDoc()).isNull();
		assertThat(json.echo(null)).isNull();
	}

	@Test
	@DisplayName("driver 19: a document above 32 KB, both ways")
	void driver_19_large_document() {
		Json json = jsonWithDriver19();
		assertThat(json.big(BigDecimal.valueOf(100_000))).hasSize(100_000 + "{\"text\":\"\"}".length());
		String sent = "{\"text\":\"" + "y".repeat(100_000) + "\"}";
		assertThat(json.lengthOf(sent)).isEqualByComparingTo(String.valueOf(sent.length()));
	}

	@Test
	@DisplayName("driver 19: binding by name")
	void driver_19_by_name() {
		assumeTrue(DRIVER_MAJOR_VERSION < 21, "a driver before 21, which does not know the JSON type");
		assertThat(pkg(JsonByName.class).echo("{\"a\":1}")).isEqualTo("{\"a\":1}");
	}

	@Test
	@DisplayName("driver 19: a cursor with a JSON column fails inside the driver, before any column is seen; the message"
			+ " names the likely type and the way around it, and the connection is discarded. On 23ai only: on 21c, the"
			+ " driver hangs instead")
	void driver_19_cursor_column() {
		assumeTrue(DATABASE_MAJOR_VERSION >= 23, "on 21c, driver 19 hangs reading such a cursor");
		Json json = jsonWithDriver19();
		// twice: the driver fails with an AssertionError or an ArrayIndexOutOfBoundsException, as its state goes
		for (int call = 1; call <= 2; call++) {
			// the driver's own failure, not the routine's (#141)
			assertThatThrownBy(json::rows)
					.isExactlyInstanceOf(io.github.rponte.penitent.exception.PlsqlExecutionException.class)
					.hasMessageContaining("a column of a type that driver 19 does not know, such as JSON, makes it fail:"
							+ " convert it in the query, as in JSON_SERIALIZE(doc RETURNING CLOB), or use driver 21 or"
							+ " later. The connection was discarded");
			assertThat(json.echo("{\"after\":1}")).as("the pool is healthy after it").isEqualTo("{\"after\":1}");
		}
	}

	@Test
	@DisplayName("driver 19: an OUT and an IN OUT, which only the JSON type of drivers 21 and later bring back, are"
			+ " refused before the call")
	void driver_19_out_refused() {
		Json json = jsonWithDriver19();
		assertThatThrownBy(() -> json.outDoc(new PlsqlOut<>()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_DOC of PLSQL_TEST.FIXTURE_JSON.OUT_DOC is an OUT of Oracle's JSON type, which"
						+ " driver 19 cannot bring back: use driver 21 or later, or call the routine through a"
						+ " @PlsqlBlock");
		assertThatThrownBy(() -> json.wrap(PlsqlOut.of("{}")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("is an IN/OUT of Oracle's JSON type");
	}
}
