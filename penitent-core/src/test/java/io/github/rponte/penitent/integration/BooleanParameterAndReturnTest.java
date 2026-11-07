package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/** {@link PlsqlBoolean} outside a composite type, and a number as boolean without the annotation. */
class BooleanParameterAndReturnTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Routines {
		@PlsqlRoutine("ECHO_TEXT")
		String sN(@PlsqlBoolean(trueValue = "S", falseValue = "N") boolean value);

		@PlsqlRoutine("ECHO_TEXT")
		String sNWrapper(@PlsqlBoolean(trueValue = "S", falseValue = "N") Boolean value);

		@PlsqlRoutine("ECHO_NUMBER")
		int numericLiterals(@PlsqlBoolean(trueValue = "5", falseValue = "9") boolean value);

		@PlsqlRoutine("ECHO_NUMBER")
		int literalThatIsNotANumber(@PlsqlBoolean(trueValue = "S", falseValue = "N") boolean value);

		@PlsqlRoutine("NEGATE")
		boolean annotationOnBoolean(@PlsqlBoolean(trueValue = "S", falseValue = "N") boolean value);

		@PlsqlRoutine("ECHO_TEXT")
		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		boolean resultSN(String value);

		@PlsqlRoutine("ECHO_TEXT")
		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		Boolean resultSNWrapper(String value);

		@PlsqlRoutine("ECHO_NUMBER")
		boolean numberResult(int value);

		@PlsqlRoutine("ECHO_NUMBER")
		Boolean wrapperNumberResult(Integer value);

		@PlsqlRoutine("ECHO_NUMBER")
		boolean primitiveNumberResult(Integer value);

		@PlsqlRoutine("ECHO_NUMBER")
		@PlsqlBoolean(trueValue = "5", falseValue = "9")
		boolean numberResultWithLiterals(int value);

		@PlsqlRoutine("IS_POSITIVE")
		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		boolean annotationOnBooleanResult(int value);
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Collections {
		@PlsqlRoutine("ECHO_NAMES")
		List<String> parameter(@PlsqlBoolean(trueValue = "S", falseValue = "N") List<Boolean> value);

		@PlsqlRoutine("ECHO_NAMES")
		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		List<Boolean> result(List<String> value);

		@PlsqlRoutine("ECHO_NUMBERS")
		List<Boolean> numbers(int[] value);
	}

	interface Blocks {
		@PlsqlBlock("begin :result := :value; end;")
		String parameterSN(@PlsqlBoolean(trueValue = "S", falseValue = "N") Boolean value);

		@PlsqlBlock("begin :result := 'S'; end;")
		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		boolean resultSN();

		@PlsqlBlock("begin :result := 1; end;")
		@PlsqlBoolean(trueValue = "1", falseValue = "0")
		boolean numericResult();

		@PlsqlBlock("begin :result := 'X'; end;")
		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		boolean invalidResult();
	}

	private final Routines routines = pkg(Routines.class);
	private final Collections collections = pkg(Collections.class);
	private final Blocks blocks = pkg(Blocks.class);

	@Test
	@DisplayName("a VARCHAR2 parameter uses the annotation literals, and null stays null")
	void parameter_with_literals() {
		assertThat(routines.sN(true)).isEqualTo("S");
		assertThat(routines.sN(false)).isEqualTo("N");
		assertThat(routines.sNWrapper(Boolean.TRUE)).isEqualTo("S");
		assertThat(routines.sNWrapper(null)).isNull();
	}

	@Test
	@DisplayName("a NUMBER parameter uses the annotation numeric literals")
	void number_parameter_with_literals() {
		assertThat(routines.numericLiterals(true)).isEqualTo(5);
		assertThat(routines.numericLiterals(false)).isEqualTo(9);
	}

	@Test
	@DisplayName("a non-numeric literal in a NUMBER parameter is an error that names the parameter")
	void number_parameter_with_invalid_literal() {
		assertThatThrownBy(() -> routines.literalThatIsNotANumber(true))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("P_VALUE")
				.hasMessageContaining("'S'");
	}

	@Test
	@DisplayName("on a data dictionary BOOLEAN the annotation has no effect, in the parameter and in the return")
	void annotation_on_boolean() {
		assertThat(routines.annotationOnBoolean(true)).isFalse();
		assertThat(routines.annotationOnBooleanResult(1)).isTrue();
	}

	@Test
	@DisplayName("a VARCHAR2 return uses the annotation literals")
	void return_with_literals() {
		assertThat(routines.resultSN("S")).isTrue();
		assertThat(routines.resultSN("N")).isFalse();
		assertThat(routines.resultSNWrapper(null)).isNull();
	}

	@Test
	@DisplayName("with declared literals, the default Y no longer applies: reading a Y is an error")
	void return_outside_the_literals() {
		assertThatThrownBy(() -> routines.resultSN("Y"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("ECHO_TEXT")
				.hasMessageContaining("'Y'")
				.hasMessageContaining("'S'");
	}

	@Test
	@DisplayName("NUMBER return as boolean: 1 and 0, null becomes null, and a primitive rejects NULL")
	void number_return() {
		assertThat(routines.numberResult(1)).isTrue();
		assertThat(routines.numberResult(0)).isFalse();
		assertThat(routines.wrapperNumberResult(1)).isTrue();
		assertThat(routines.wrapperNumberResult(null)).isNull();
		assertThatThrownBy(() -> routines.primitiveNumberResult(null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in boolean")
				.hasMessageContaining("declare Boolean");
	}

	@Test
	@DisplayName("a NUMBER return other than 1 and 0 is an error, instead of false")
	void invalid_number_return() {
		assertThatThrownBy(() -> routines.numberResult(2))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("ECHO_NUMBER")
				.hasMessageContaining("'2'")
				.hasMessageContaining("'1'");
	}

	@Test
	@DisplayName("a NUMBER return with declared literals uses the literals")
	void number_return_with_literals() {
		assertThat(routines.numberResultWithLiterals(5)).isTrue();
		assertThat(routines.numberResultWithLiterals(9)).isFalse();
		assertThatThrownBy(() -> routines.numberResultWithLiterals(1)).isInstanceOf(PlsqlMappingException.class);
	}

	@Test
	@DisplayName("collection: the literals apply per element, in both directions")
	void collections() {
		assertThat(collections.parameter(Arrays.asList(true, false, null))).containsExactly("S", "N", null);
		assertThat(collections.result(Arrays.asList("S", "N", null))).containsExactly(true, false, null);
		assertThat(collections.numbers(new int[] { 1, 0 })).containsExactly(true, false);
	}

	@Test
	@DisplayName("block: the annotation replaces BOOLEAN with the literal, in the parameter and in :result")
	void blocks() {
		assertThat(blocks.parameterSN(true)).isEqualTo("S");
		assertThat(blocks.parameterSN(false)).isEqualTo("N");
		assertThat(blocks.parameterSN(null)).isNull();
		assertThat(blocks.resultSN()).isTrue();
		assertThat(blocks.numericResult()).isTrue();
		assertThatThrownBy(blocks::invalidResult)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("invalidResult")
				.hasMessageContaining("'X'");
	}
}
