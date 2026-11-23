package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The JSON text a parameter of Oracle's JSON type takes, checked before the call the same way with every driver (#140):
 * the JSON specification's syntax, strictly, an empty text, duplicate keys, a number beyond {@code NUMBER}'s range and
 * a lone surrogate. Without the check, drivers 21 and 23 refused such a text with errors of their own, and with driver
 * 19 the database took "almost JSON" and corrected it silently. The syntax messages are the driver's text parser's,
 * whose first sentence is the same in drivers 19, 21 and 23, measured; driver 23 only adds an {@code ORA-} code.
 */
class JsonCodecTest {

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
	@DisplayName("strict syntax, which the database's lax JSON() took with driver 19: the parser's first sentence, with"
			+ " the line and the column")
	void syntax(String what, String text, String sentence) {
		assertThatThrownBy(() -> JsonCodec.text(text))
				.isInstanceOf(ScalarCodec.ConversionException.class)
				.hasMessage("the text is not JSON: " + sentence);
	}

	@Test
	@DisplayName("the message keeps only the parser's first sentence, the same in every driver; the parser's own failure,"
			+ " with driver 23's ORA- code and its link, stays as the cause")
	void the_parsers_failure_is_the_cause() {
		assertThatThrownBy(() -> JsonCodec.text("{bad"))
				.hasMessage("the text is not JSON: Unexpected character 'b' at line 1, column 2")
				.hasMessageNotContaining("ORA-")
				.cause().isInstanceOf(oracle.sql.json.OracleJsonException.class)
				.hasMessageContaining("Unexpected character 'b' at line 1, column 2");
	}

	@Test
	@DisplayName("a raw control character in a string, and a byte order mark, are not JSON either")
	void raw_characters() {
		assertThatThrownBy(() -> JsonCodec.text("{\"s\":\"a\tb\"}"))
				.hasMessage("the text is not JSON: Unexpected character 0x9 at line 1, column 8");
		assertThatThrownBy(() -> JsonCodec.text("﻿{\"a\":1}"))
				.hasMessage("the text is not JSON: Unexpected character 0xfeff at line 1, column 1");
	}

	@Test
	@DisplayName("an empty or a blank text is not JSON: SQL NULL is null")
	void empty() {
		assertThatThrownBy(() -> JsonCodec.text(""))
				.isInstanceOf(ScalarCodec.ConversionException.class)
				.hasMessage("the text is empty, which is not JSON: send null for SQL NULL");
		assertThatThrownBy(() -> JsonCodec.text("   ")).hasMessage("the text is empty, which is not JSON: send null for SQL"
				+ " NULL");
		assertThat(JsonCodec.text(null)).isNull();
	}

	@Test
	@DisplayName("a key twice in the same object, at the top or nested, naming where; the same key in two objects is fine")
	void duplicate_keys() {
		assertThatThrownBy(() -> JsonCodec.text("{\"a\":1,\"a\":2}"))
				.hasMessage("$ has the key \"a\" twice, and the keys of a JSON object are unique");
		assertThatThrownBy(() -> JsonCodec.text("{\"o\":{\"a\":1,\"a\":2}}"))
				.hasMessage("$.o has the key \"a\" twice, and the keys of a JSON object are unique");
		assertThatThrownBy(() -> JsonCodec.text("[{}, {\"x y\":[{\"a\":1,\"a\":2}]}]"))
				.hasMessage("$[1][\"x y\"][0] has the key \"a\" twice, and the keys of a JSON object are unique");
		assertThat(JsonCodec.text("{\"o\":{\"a\":1},\"p\":{\"a\":2}}")).isEqualTo("{\"o\":{\"a\":1},\"p\":{\"a\":2}}");
	}

	@Test
	@DisplayName("a number beyond NUMBER's range, which the JSON type stores numbers as, too large or too small, naming"
			+ " where")
	void numbers_beyond() {
		assertThatThrownBy(() -> JsonCodec.text("{\"big\":1e200}"))
				.hasMessage("$.big is 1E+200, beyond the range of Oracle's NUMBER, whose magnitude goes from 1E-130 to just"
						+ " under 1E+126, besides 0, which the JSON type stores numbers as: keep such a number as a JSON"
						+ " string");
		assertThatThrownBy(() -> JsonCodec.text("{\"a\":[1, 2, -1e-200]}"))
				.hasMessageStartingWith("$.a[2] is -1E-200, beyond the range of Oracle's NUMBER");
		assertThatThrownBy(() -> JsonCodec.text("1e126")).hasMessageStartingWith("$ is 1E+126, beyond the range");
		assertThat(JsonCodec.text("{\"n\":9.99e125,\"m\":1e-130}")).isEqualTo("{\"n\":9.99e125,\"m\":1e-130}");
	}

	@Test
	@DisplayName("a lone surrogate, in a value or in a key, which drivers 21 and 23 silently turn into '?'")
	void lone_surrogate() {
		assertThatThrownBy(() -> JsonCodec.text("{\"s\":\"a\\uD800b\"}"))
				.hasMessage("$.s holds a lone UTF-16 surrogate, \\uD800, which is no character, and which the driver would"
						+ " store as '?': send the whole character, both surrogates");
		assertThatThrownBy(() -> JsonCodec.text("{\"k\\uDC00\":1}"))
				.hasMessageStartingWith("$ has a key that holds a lone UTF-16 surrogate, \\uDC00");
		assertThat(JsonCodec.text("{\"s\":\"\\uD83D\\uDE00\"}")).as("a pair is a character").isNotNull();
	}

	@Test
	@DisplayName("what JSON allows goes as it is: a string, a number, null and true at the top, an escaped NUL, deep"
			+ " nesting and a long key, whose limits are the driver's and the database's")
	void valid() {
		for (String text : List.of("\"abc\"", "42", "null", "true", "{\"s\":\"a\\u0000b\"}", "{ \"b\" : 2 }",
				"[".repeat(10_000) + "]".repeat(10_000), "{\"" + "k".repeat(70_000) + "\":1}")) {
			assertThat(JsonCodec.text(text)).isSameAs(text);
		}
	}

	@Test
	@DisplayName("a Map: a number beyond NUMBER's range, and a lone surrogate, in a value or a key, naming where")
	void map() {
		Map<String, Object> big = new LinkedHashMap<>();
		big.put("items", List.of(1, new BigDecimal("1E+200")));
		assertThatThrownBy(() -> JsonCodec.text(big)).hasMessageStartingWith("$.items[1] is 1E+200, beyond the range");
		assertThatThrownBy(() -> JsonCodec.text(Map.of("d", 1e-200))).hasMessageStartingWith("$.d is 1.0E-200, beyond");
		assertThatThrownBy(() -> JsonCodec.text(Map.of("s", "a\uD800")))
				.hasMessageStartingWith("$.s holds a lone UTF-16 surrogate, \\uD800");
		assertThatThrownBy(() -> JsonCodec.text(Map.of("k\uDC00", 1)))
				.hasMessageStartingWith("$ has a key that holds a lone UTF-16 surrogate, \\uDC00");
	}
}
