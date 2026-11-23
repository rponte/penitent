package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.isNull;
import static io.github.rponte.penitent.mock.PlsqlMocks.never;
import static io.github.rponte.penitent.mock.PlsqlMocks.out;
import static io.github.rponte.penitent.mock.PlsqlMocks.times;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * Stubs and {@code verify} with Oracle's {@code JSON} type, from 21c on (#133). A stub's value is written as
 * {@code JSON('...')}, after the core's strict check (#140), so a stub refuses what a call refuses. Matching by value
 * is document equality, {@code JSON_EQUAL}: the order of an object's members, the spacing and a number's form do not
 * count; an array's order does. A call is recorded as {@code {"v": document}}, so that {@code verify} tells SQL
 * {@code NULL} from the JSON {@code null}, and compares objects with objects, which 21c needs for a scalar at the top.
 */
class StubJsonTest {

	private static final int DRIVER_MAJOR_VERSION = new oracle.jdbc.OracleDriver().getMajorVersion();

	@PlsqlPackage("FREIGHT_DOCS")
	interface Docs {
		String quoteOf(String pOrder);

		@PlsqlRoutine("QUOTE_OF")
		Map<String, Object> quoteMap(Map<String, Object> pOrder);

		void labelOf(String pOrder, PlsqlOut<String> pLabel);

		void wrapDoc(PlsqlOut<String> pDoc);

		List<Object> listOf(List<Object> pItems);

		String describeQuote(String pOrder);
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Docs docs;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() throws Exception {
		try (var connection = TestDatabase.pool().getConnection()) {
			assumeTrue(connection.getMetaData().getDatabaseMajorVersion() >= 21, "the JSON type exists only from 21c on");
		}
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		docs = PlsqlPackages.create(Docs.class, dataSource);
	}

	@AfterEach
	void end() {
		if (mocks != null) {
			mocks.end();
		}
	}

	@Test
	@DisplayName("a stub returns a document, which comes back as the database writes it")
	void returns_a_document() {
		when(mocks.mock(Docs.class).quoteOf(any())).thenReturn("{ \"total\" : 24.90 }");

		assertThat(docs.quoteOf("{}")).isEqualTo("{\"total\":24.9}");
	}

	@Test
	@DisplayName("a stub returns a Map, and null")
	void returns_a_map_and_null() {
		Docs mock = mocks.mock(Docs.class);
		when(mock.quoteMap(Map.of("id", 1))).thenReturn(Map.of("total", new BigDecimal("24.90")));
		when(mock.quoteMap(Map.of("id", 2))).thenReturn(null);

		assertThat(docs.quoteMap(Map.of("id", 1))).isEqualTo(Map.of("total", new BigDecimal("24.9")));
		assertThat(docs.quoteMap(Map.of("id", 2))).isNull();
	}

	@Test
	@DisplayName("a stub returns a document above 32 KB")
	void returns_a_large_document() {
		String large = "{\"text\":\"" + "x".repeat(40_000) + "\"}";
		when(mocks.mock(Docs.class).quoteOf(any())).thenReturn(large);

		assertThat(docs.quoteOf("{}")).isEqualTo(large);
	}

	@Test
	@DisplayName("PL/SQL calling the stubbed function gets the stubbed document")
	void called_from_plsql() {
		when(mocks.mock(Docs.class).quoteOf(any())).thenReturn("{\"total\":1}");

		assertThat(docs.describeQuote("{}")).isEqualTo("{\"total\":1}");
	}

	@Test
	@DisplayName("a stub matches a document by its value: an object's member order, the spacing and a number's form do"
			+ " not count, an array's order does")
	void matches_by_document() {
		when(mocks.mock(Docs.class).quoteOf("{\"a\":1,\"b\":[1,2]}")).thenReturn("{\"hit\":true}");

		assertThat(docs.quoteOf("{ \"b\" : [1, 2], \"a\" : 1.0 }")).isEqualTo("{\"hit\":true}");
		assertThatThrownBy(() -> docs.quoteOf("{\"a\":1,\"b\":[2,1]}"))
				.isInstanceOf(PlsqlRoutineRaisedException.class)
				.hasMessageContaining("no stub matches the arguments");
	}

	@Test
	@DisplayName("a stub matches a Map, a scalar at the top, and SQL NULL apart from the JSON null")
	void matches_a_map_a_scalar_and_null() {
		Docs mock = mocks.mock(Docs.class);
		Map<String, Object> order = new LinkedHashMap<>();
		order.put("id", 7);
		when(mock.quoteMap(order)).thenReturn(Map.of("hit", "map"));
		when(mock.quoteOf("42")).thenReturn("\"scalar\"");
		when(mock.quoteOf(isNull())).thenReturn("\"sql null\"");
		when(mock.quoteOf("null")).thenReturn("\"json null\"");

		assertThat(docs.quoteMap(Map.of("id", new BigDecimal("7.0")))).isEqualTo(Map.of("hit", "map"));
		assertThat(docs.quoteOf("42.0")).isEqualTo("\"scalar\"");
		assertThat(docs.quoteOf(null)).isEqualTo("\"sql null\"");
		assertThat(docs.quoteOf("null")).isEqualTo("\"json null\"");
	}

	@Test
	@DisplayName("a stub sets a JSON OUT, with driver 21 or later; with driver 19 such a stub is refused when made, as"
			+ " the call would be, since the core describes the method then")
	void sets_an_out() {
		Docs mock = mocks.mock(Docs.class);
		if (DRIVER_MAJOR_VERSION < 21) {
			assertThatThrownBy(() -> doNothing().when(mock).labelOf(eq("{}"), out("{\"label\":\"A\"}")))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("P_LABEL of PLSQL_TEST.FREIGHT_DOCS.LABEL_OF is an OUT of Oracle's JSON type,"
							+ " which driver 19 cannot bring back");
			return;
		}
		doNothing().when(mock).labelOf(eq("{}"), out("{\"label\":\"A\"}"));
		PlsqlOut<String> label = new PlsqlOut<>();
		docs.labelOf("{}", label);
		assertThat(label.get()).isEqualTo("{\"label\":\"A\"}");
	}

	@Test
	@DisplayName("a stub sets a JSON IN OUT, with driver 21 or later; with driver 19 such a stub is refused when made."
			+ " Matching by the document that came in waits for #150")
	void sets_an_in_out() {
		Docs mock = mocks.mock(Docs.class);
		if (DRIVER_MAJOR_VERSION < 21) {
			assertThatThrownBy(() -> doNothing().when(mock).wrapDoc(out("{\"wrapped\":true}")))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("P_DOC of PLSQL_TEST.FREIGHT_DOCS.WRAP_DOC is an IN/OUT of Oracle's JSON type");
			return;
		}
		doNothing().when(mock).wrapDoc(out("{\"wrapped\":true}"));
		PlsqlOut<String> doc = PlsqlOut.of("{\"a\":1}");
		docs.wrapDoc(doc);

		assertThat(doc.get()).isEqualTo("{\"wrapped\":true}");
	}

	@Test
	@DisplayName("a List at the top, as a stub's value and as a matcher's, by document equality")
	void list_at_the_top() {
		Docs mock = mocks.mock(Docs.class);
		when(mock.listOf(List.of(1, "a"))).thenReturn(List.of(new BigDecimal("2.50"), true));

		assertThat(docs.listOf(List.of(new BigDecimal("1.0"), "a"))).containsExactly(new BigDecimal("2.5"), true);
		verify(mock).listOf(List.of(1, "a"));
		verify(mock, never()).listOf(List.of("a", 1));
	}

	@Test
	@DisplayName("verify counts calls by document equality, a scalar at the top included, on 21c as on 23ai")
	void verify_by_document() {
		Docs mock = mocks.mock(Docs.class);
		when(mock.quoteOf(any())).thenReturn("{}");
		docs.quoteOf("{\"b\":2,\"a\":1}");
		docs.quoteOf("{ \"a\" : 1.0, \"b\" : 2 }");
		docs.quoteOf("42");
		docs.quoteOf("[1,2]");

		verify(mock, times(2)).quoteOf("{\"a\":1,\"b\":2}");
		verify(mock).quoteOf("42.0");
		verify(mock).quoteOf("[1,2]");
		verify(mock, never()).quoteOf("[2,1]");
	}

	@Test
	@DisplayName("verify tells SQL NULL from the JSON null document")
	void verify_null_apart_from_json_null() {
		Docs mock = mocks.mock(Docs.class);
		when(mock.quoteOf(any())).thenReturn("{}");
		docs.quoteOf(null);
		docs.quoteOf("null");
		docs.quoteOf("null");

		verify(mock).quoteOf(isNull());
		verify(mock, times(2)).quoteOf("null");
	}

	@Test
	@DisplayName("verify counts a Map by document equality")
	void verify_a_map() {
		Docs mock = mocks.mock(Docs.class);
		when(mock.quoteMap(any())).thenReturn(Map.of());
		docs.quoteMap(Map.of("id", 7));

		verify(mock).quoteMap(Map.of("id", new BigDecimal("7.00")));
	}

	@Test
	@DisplayName("a stub's text is checked as a call's is: a returned value, a matched one and an OUT, the database's lax"
			+ " JSON() never reached")
	void stub_values_are_checked() {
		Docs mock = mocks.mock(Docs.class);
		assertThatThrownBy(() -> when(mock.quoteOf(any())).thenReturn("{'a':1}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the text is not JSON: Unexpected character ''' at line 1, column 2");
		assertThatThrownBy(() -> when(mock.quoteOf(any())).thenReturn("{\"n\":01}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the text is not JSON");
		assertThatThrownBy(() -> when(mock.quoteOf("{\"a\":1,\"a\":2}")).thenReturn("{}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("$ has the key \"a\" twice");
		assertThatThrownBy(() -> when(mock.quoteOf(any())).thenReturn("{\"big\":1e200}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("$.big is 1E+200, beyond the range of Oracle's NUMBER");
		assertThatThrownBy(() -> when(mock.quoteMap(Map.of("s", "a\uD800"))).thenReturn(Map.of()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("$.s holds a lone UTF-16 surrogate, \\uD800");
		assertThatThrownBy(() -> when(mock.quoteMap(any())).thenReturn(Map.of("tiny", 1e-200)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("$.tiny is 1.0E-200, beyond the range of Oracle's NUMBER");
		if (DRIVER_MAJOR_VERSION >= 21) {
			// with driver 19, a stub of a JSON OUT is refused before its value is looked at: see sets_an_out
			assertThatThrownBy(() -> doNothing().when(mock).labelOf(any(), out("")))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("the text is empty, which is not JSON");
		}
	}

	@Test
	@DisplayName("the example of the testing page")
	void documented_example() {
		Docs mock = mocks.mock(Docs.class);
		// snippet: testing/json
		// a document matches by its value: { "id" : 7.0 } matches too
		when(mock.quoteOf("{\"id\":7}")).thenReturn("{\"total\":24.9}");
		// a Map, a List or a scalar, as in a call
		when(mock.quoteMap(Map.of("id", 8))).thenReturn(Map.of("total", new BigDecimal("12.5")));
		// end-snippet
		assertThat(docs.quoteOf("{ \"id\" : 7.0 }")).isEqualTo("{\"total\":24.9}");
		assertThat(docs.quoteMap(Map.of("id", 8))).isEqualTo(Map.of("total", new BigDecimal("12.5")));
		docs.quoteOf("{\"id\":7}");
		// snippet: testing/json-verify
		verify(mock, times(2)).quoteOf("{\"id\":7}");
		// isNull() is SQL NULL only, not the JSON null, which is "null"
		verify(mock, never()).quoteOf(isNull());
		// end-snippet
	}
}
