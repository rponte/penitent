package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * What a JSON text sent to a routine does, by driver and database, as the Types page's "Moving between drivers and
 * databases" describes it. Drivers 21 and later parse the text themselves, and with driver 19 the database parses it,
 * in {@code JSON(?)}, lax by default. So the library checks the text first, the same way with every driver (#140):
 * the JSON specification's syntax, an empty text, duplicate keys, a number beyond {@code NUMBER}'s range and a lone
 * surrogate are refused before the call, with one exception class and one message everywhere. What stays different
 * is pinned here too: the limits of the binary form, a key's length and the depth of nesting, which each driver and
 * database sets by version, and which the library leaves to them.
 */
class JsonPortabilityTest extends OracleIntegrationTest {

	private static final int DRIVER_MAJOR_VERSION = new oracle.jdbc.OracleDriver().getMajorVersion();

	private static final String WHERE = "P_DOC of PLSQL_TEST.FIXTURE_JSON.ECHO: ";

	@PlsqlPackage("FIXTURE_JSON")
	interface Json {
		String echo(String pDoc);

		String built(String pKind);

		@PlsqlRoutine("BUILT")
		Map<String, Object> builtMap(String pKind);
	}

	@BeforeEach
	void needsJson() {
		assumeTrue(DATABASE_MAJOR_VERSION >= 21, "the JSON type exists only from Oracle 21c on");
	}

	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = {
			"not JSON          | {bad                | Unexpected character 'b' at line 1, column 2",
			"truncated         | [1, 2               | Invalid token EOF at line 1, column 19",
			"trailing text     | {\"a\":1} trailing  | Unexpected character 'a' at line 1, column 11",
			"leading zero      | {\"n\":01}          | Invalid token NUMBER at line 1, column 7",
			"NaN               | {\"n\":NaN}         | Unexpected character 'N' at line 1, column 6",
			"single quotes     | {'a':1}             | Unexpected character ''' at line 1, column 2",
			"a comment         | {\"a\":1 /* c */}   | Unexpected character '/' at line 1, column 8",
			"a trailing comma  | {\"a\":1,}          | Invalid token CURLYCLOSE at line 1, column 8",
			"True              | {\"a\":True}        | Unexpected character 'T' at line 1, column 6" })
	@DisplayName("the same everywhere: a text that is not JSON is refused before the call, though with driver 19 the"
			+ " database took and corrected some of it, a leading zero, single quotes, a trailing comma, True, and on 23ai"
			+ " a comment and NaN")
	void not_json(String what, String text, String sentence) {
		assertThatThrownBy(() -> pkg(Json.class).echo(text))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(WHERE + "the text is not JSON: " + sentence)
				.hasMessageNotContaining("ORA-");
	}

	@Test
	@DisplayName("the same everywhere: a raw tab in a string and a byte order mark, which driver 19 took on 23ai")
	void raw_characters() {
		assertThatThrownBy(() -> pkg(Json.class).echo("{\"s\":\"a\tb\"}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(WHERE + "the text is not JSON: Unexpected character 0x9 at line 1, column 8");
		assertThatThrownBy(() -> pkg(Json.class).echo("\uFEFF{\"a\":1}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(WHERE + "the text is not JSON: Unexpected character 0xfeff at line 1, column 1");
	}

	@Test
	@DisplayName("the same everywhere: an empty or a blank text is refused, which driver 19 sent as SQL NULL")
	void empty_string() {
		for (String text : new String[] { "", "   " }) {
			assertThatThrownBy(() -> pkg(Json.class).echo(text))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining(WHERE + "the text is empty, which is not JSON: send null for SQL NULL");
		}
		assertThat(pkg(Json.class).echo(null)).isNull();
	}

	@Test
	@DisplayName("the same everywhere: duplicate keys, whose error code changed with the driver and, with driver 19, with"
			+ " the database")
	void duplicate_keys() {
		assertThatThrownBy(() -> pkg(Json.class).echo("{\"a\":1,\"a\":2}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(WHERE + "$ has the key \"a\" twice, and the keys of a JSON object are unique");
		assertThatThrownBy(() -> pkg(Json.class).echo("{\"o\":{\"a\":1,\"a\":2}}"))
				.hasMessageContaining(WHERE + "$.o has the key \"a\" twice");
	}

	@Test
	@DisplayName("the same everywhere: a number beyond NUMBER's range, which driver 19 on 23ai took, and on 21c refused"
			+ " when too large and stored as 0 when too small")
	void numbers_beyond() {
		assertThatThrownBy(() -> pkg(Json.class).echo("{\"big\":1e200}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(WHERE + "$.big is 1E+200, beyond the range of Oracle's NUMBER");
		assertThatThrownBy(() -> pkg(Json.class).echo("{\"tiny\":1e-200}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(WHERE + "$.tiny is 1E-200, beyond the range of Oracle's NUMBER");
	}

	@Test
	@DisplayName("the same everywhere: a lone surrogate, which drivers 21 and 23 stored as '?'")
	void lone_surrogate() {
		assertThatThrownBy(() -> pkg(Json.class).echo("{\"s\":\"\\uD800\"}"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining(WHERE + "$.s holds a lone UTF-16 surrogate, \\uD800");
		assertThat(pkg(Json.class).echo("{\"s\":\"\\uD83D\\uDE00\"}")).as("a pair is a character")
				.isEqualTo("{\"s\":\"\uD83D\uDE00\"}");
	}

	@Test
	@DisplayName("still different, the driver's and the database's limits: a key of 300 bytes, beyond driver 21's 256,"
			+ " and 21c's own")
	void key_length() {
		String text = "{\"" + "k".repeat(300) + "\":1}";
		if (DRIVER_MAJOR_VERSION == 21) {
			assertThatThrownBy(() -> pkg(Json.class).echo(text))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("P_DOC is Oracle's JSON type, and the driver refused the text while binding it,"
							+ " at a limit of its own")
					.hasMessageContaining("must not be greater than 256 bytes");
		} else if (DRIVER_MAJOR_VERSION < 21 && DATABASE_MAJOR_VERSION < 23) {
			assertThatThrownBy(() -> pkg(Json.class).echo(text))
					.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class,
							e -> assertThat(e.getCode()).as("ORA-40476: JSON key too long").isEqualTo(40476));
		} else {
			assertThat(pkg(Json.class).echo(text)).isEqualTo(text);
		}
	}

	@Test
	@DisplayName("still different: a key of 70,000 bytes, beyond driver 23's 65,535, driver 21's 256 and the database's")
	void key_too_long_everywhere() {
		String text = "{\"" + "k".repeat(70_000) + "\":1}";
		if (DRIVER_MAJOR_VERSION >= 21) {
			assertThatThrownBy(() -> pkg(Json.class).echo(text))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("the driver refused the text while binding it, at a limit of its own")
					.hasMessageContaining(DRIVER_MAJOR_VERSION >= 23 ? "65,535 bytes" : "256 bytes");
		} else {
			assertThatThrownBy(() -> pkg(Json.class).echo(text))
					.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class,
							e -> assertThat(e.getCode()).isEqualTo(40476));
		}
	}

	@Test
	@DisplayName("still different: 10,000 levels of nesting, which driver 23 takes, driver 21 failed on with a bare"
			+ " ArrayIndexOutOfBoundsException, and the database refuses with driver 19")
	void nesting() {
		String text = "[".repeat(10_000) + "]".repeat(10_000);
		if (DRIVER_MAJOR_VERSION >= 23) {
			assertThat(pkg(Json.class).echo(text)).isEqualTo(text);
		} else if (DRIVER_MAJOR_VERSION == 21) {
			assertThatThrownBy(() -> pkg(Json.class).echo(text))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("the driver refused the text while binding it, at a limit of its own:"
							+ " ArrayIndexOutOfBoundsException");
		} else {
			assertThatThrownBy(() -> pkg(Json.class).echo(text))
					.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class,
							e -> assertThat(e.getCode()).as("ORA-40475: JSON data nested too many levels")
									.isEqualTo(40475));
		}
	}

	@Test
	@DisplayName("the same everywhere: the text of a document built in the database, which the driver writes with"
			+ " drivers 21 and later and the database with driver 19")
	void built_text_is_the_same() {
		Json json = pkg(Json.class);
		assertThat(json.built("numbers")).isEqualTo("{\"n\":100000000000000000000,\"small\":0.000001,"
				+ "\"tiny\":0.00000015,\"neg\":0,\"dec\":24.9,\"int\":12345678901234567890,"
				+ "\"long\":1.23456789012345678901234567890123456789E+41}");
		assertThat(json.built("date")).isEqualTo("{\"d\":\"2026-11-20T00:00:00\"}");
		assertThat(json.built("timestamp")).isEqualTo("{\"ts\":\"2026-11-20T10:11:12.500000\"}");
		assertThat(json.built("time zone")).as("in UTC").isEqualTo("{\"tz\":\"2026-11-20T13:11:12.500000Z\"}");
		assertThat(json.built("binary")).isEqualTo("{\"bd\":0.1,\"bf\":0.1}");
		assertThat(json.built("raw")).as("in hex").isEqualTo("{\"raw\":\"ABCD\"}");
		assertThat(json.built("interval")).as("Oracle's, on every version").isEqualTo("{\"iv\":null}");
		assertThat(json.built("escapes")).isEqualTo("{\"s\":\"ç/€\\t\\\"x\"}");
		assertThat(json.built("division")).isEqualTo("{\"n\":0.3333333333333333333333333333333333333333}");
	}

	@Test
	@DisplayName("the same everywhere: a document built in the database, as a Map, its dates, timestamps and RAW values"
			+ " as their text")
	void built_map_is_the_same() {
		Json json = pkg(Json.class);
		Map<String, Object> numbers = new LinkedHashMap<>();
		numbers.put("n", new BigDecimal("100000000000000000000"));
		numbers.put("small", new BigDecimal("0.000001"));
		numbers.put("tiny", new BigDecimal("1.5E-7"));
		numbers.put("neg", new BigDecimal("0"));
		numbers.put("dec", new BigDecimal("24.9"));
		numbers.put("int", new BigDecimal("12345678901234567890"));
		numbers.put("long", new BigDecimal("123456789012345678901234567890123456789000"));
		assertThat(json.builtMap("numbers")).isEqualTo(numbers);
		assertThat(json.builtMap("date")).isEqualTo(Map.of("d", "2026-11-20T00:00:00"));
		assertThat(json.builtMap("timestamp")).isEqualTo(Map.of("ts", "2026-11-20T10:11:12.500000"));
		assertThat(json.builtMap("binary")).isEqualTo(Map.of("bd", new BigDecimal("0.1"), "bf", new BigDecimal("0.1")));
		assertThat(json.builtMap("raw")).isEqualTo(Map.of("raw", "ABCD"));
	}

	@Test
	@DisplayName("the same everywhere: a text sent comes back without its spacing, with its numbers' values, its escapes"
			+ " resolved, and a string or null at the top as they are")
	void sent_text_is_the_same() {
		Json json = pkg(Json.class);
		assertThat(json.echo("{ \"n\" : 1.50 , \"s\" : \"\\u00e7\\/\" }")).isEqualTo("{\"n\":1.5,\"s\":\"ç/\"}");
		assertThat(json.echo("{\"price\":24.90,\"n\":1.5e3}")).isEqualTo("{\"price\":24.9,\"n\":1500}");
		assertThat(json.echo("{\"long\":123456789012345678901234567890123456789012}"))
				.as("38 significant digits").isEqualTo("{\"long\":1.23456789012345678901234567890123456789E+41}");
		assertThat(json.echo("\"abc\"")).isEqualTo("\"abc\"");
		assertThat(json.echo("null")).as("the JSON null, not SQL NULL").isEqualTo("null");
	}
}
