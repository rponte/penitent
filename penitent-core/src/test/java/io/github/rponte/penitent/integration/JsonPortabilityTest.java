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

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * What a JSON text sent to a routine does, by driver and database, as the Types page's "Moving between drivers and
 * databases" describes it. Drivers 21 and later parse the text themselves; with driver 19, the database parses it,
 * in {@code JSON(?)}. These tests pin today's differences: making them the same everywhere is #140, which turns them
 * red on purpose, and then the page changes with them.
 */
class JsonPortabilityTest extends OracleIntegrationTest {

	private static final int DRIVER_MAJOR_VERSION = new oracle.jdbc.OracleDriver().getMajorVersion();

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

	@Test
	@DisplayName("an empty string: refused by drivers 21 and later, NULL with driver 19, as Oracle's '' is NULL")
	void empty_string() {
		Json json = pkg(Json.class);
		if (DRIVER_MAJOR_VERSION >= 23) {
			assertThatThrownBy(() -> json.echo("")).isInstanceOf(PlsqlExecutionException.class)
					.hasMessageContaining("ORA-26328: Invalid token EOF");
		} else if (DRIVER_MAJOR_VERSION >= 21) {
			assertThatThrownBy(() -> json.echo("")).isInstanceOf(PlsqlExecutionException.class)
					.hasMessageContaining("Invalid token EOF").hasMessageNotContaining("ORA-");
		} else {
			assertThat(json.echo("")).isNull();
		}
	}

	@Test
	@DisplayName("text that is not JSON: the driver's error with drivers 21 and later, the database's with driver 19,"
			+ " reported as raised by the routine")
	void not_json() {
		Json json = pkg(Json.class);
		if (DRIVER_MAJOR_VERSION >= 23) {
			assertThatThrownBy(() -> json.echo("{bad")).isInstanceOf(PlsqlExecutionException.class)
					.hasMessageContaining("ORA-26326: Unexpected character 'b'");
		} else if (DRIVER_MAJOR_VERSION >= 21) {
			assertThatThrownBy(() -> json.echo("{bad")).isInstanceOf(PlsqlExecutionException.class)
					.hasMessageContaining("Unexpected character 'b'").hasMessageNotContaining("ORA-");
		} else {
			assertThatThrownBy(() -> json.echo("{bad")).isInstanceOf(PlsqlRoutineRaisedException.class)
					.hasMessageContaining("ORA-40441: JSON syntax error");
		}
	}

	@Test
	@DisplayName("duplicate keys: the driver's error with drivers 21 and later, and with driver 19 the database's,"
			+ " whose code changes with the database")
	void duplicate_keys() {
		Json json = pkg(Json.class);
		String doc = "{\"a\":1,\"a\":2}";
		if (DRIVER_MAJOR_VERSION >= 23) {
			assertThatThrownBy(() -> json.echo(doc)).isInstanceOf(PlsqlExecutionException.class)
					.hasMessageContaining("ORA-26323: JSON object contains duplicate key: a");
		} else if (DRIVER_MAJOR_VERSION >= 21) {
			assertThatThrownBy(() -> json.echo(doc)).isInstanceOf(PlsqlExecutionException.class)
					.hasMessageContaining("JSON object contains duplicate key: a").hasMessageNotContaining("ORA-");
		} else {
			assertThatThrownBy(() -> json.echo(doc)).isInstanceOf(PlsqlRoutineRaisedException.class)
					.hasMessageContaining(DATABASE_MAJOR_VERSION >= 23 ? "ORA-40444: JSON processing error"
							: "ORA-40473: duplicate key names in JSON object");
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
