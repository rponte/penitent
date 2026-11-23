package io.github.rponte.penitent.mock.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/**
 * The PL/SQL a stub's body writes for Oracle's JSON type (#133): a value as {@code JSON('...')}, and a recorded
 * argument as {@code {"v": document}}. {@code StubJsonTest} runs them in the database; this pins the text.
 */
class BodyRewriterJsonTest {

	private static final Argument DOC = new Argument(1, "P_ORDER", "JSON", "IN", null, null, null, false);
	private static final Argument NAME = new Argument(1, "P_NAME", "VARCHAR2", "IN", null, null, null, false);

	@Test
	@DisplayName("a JSON value is written as JSON('...'), its text a literal; NULL stays SQL NULL")
	void json_value() {
		assertThat(BodyRewriter.written(DOC, "{\"a\":1}")).isEqualTo("JSON('{\"a\":1}')");
		assertThat(BodyRewriter.written(DOC, "{\"s\":\"it's\"}")).isEqualTo("JSON('{\"s\":\"it''s\"}')");
		assertThat(BodyRewriter.written(DOC, null)).isEqualTo("NULL");
	}

	@Test
	@DisplayName("a long JSON text keeps the TO_CLOB pieces of a long text, inside JSON(...)")
	void long_json_value() {
		String large = "{\"t\":\"" + "x".repeat(40_000) + "\"}";
		String written = BodyRewriter.written(DOC, large);
		assertThat(written).startsWith("JSON(TO_CLOB('").endsWith("'))").contains("') || TO_CLOB('");
	}

	@Test
	@DisplayName("any other type is written as before")
	void other_types() {
		assertThat(BodyRewriter.written(NAME, "SP")).isEqualTo("'SP'");
	}

	@Test
	@DisplayName("a JSON argument is recorded as an object, whose member is absent for SQL NULL")
	void recorded() {
		assertThat(BodyRewriter.recordedJson("P_ORDER")).isEqualTo("JSON_OBJECT('v' VALUE P_ORDER ABSENT ON NULL)");
	}
}
