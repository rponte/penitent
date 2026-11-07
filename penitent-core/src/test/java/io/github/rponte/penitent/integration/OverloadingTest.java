package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlType;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/** Each Java method calls its own version of the routine, chosen by its signature. */
class OverloadingTest extends OracleIntegrationTest {

	record Address(String street, Integer num) {
	}

	@PlsqlType("ADDRESS_T")
	record AnnotatedAddress(String street, Integer num) {
	}

	@PlsqlPackage("FIXTURE_OVERLOAD")
	interface Overload {
		String which(Integer value);

		String which(String value);

		String which(LocalDate value);

		String which(Address value);

		String which(List<String> value);

		String which(int a, int b);

		@PlsqlRoutine("WITH_DEFAULT")
		String withDefaultText(String value);

		@PlsqlRoutine("WITH_DEFAULT")
		String withDefaultOmitted(int a);

		@PlsqlRoutine("WITH_DEFAULT")
		String withDefaultSupplied(int a, int b);

		String noArgument();

		@PlsqlRoutine("NO_ARGUMENT")
		String withArgument(int value);

		void procNoArgument();

		@PlsqlRoutine("PROC_NO_ARGUMENT")
		void procWithArgument(int value);

		@PlsqlRoutine("FUNCTION_OR_PROCEDURE")
		void asProcedure(int value);

		@PlsqlRoutine("FUNCTION_OR_PROCEDURE")
		String asFunction(int value);

		String sameJava(int value);

		@PlsqlRoutine("TWO_OBJECTS")
		String objectWithoutAnnotation(Address value);

		@PlsqlRoutine("TWO_OBJECTS")
		String annotatedObject(AnnotatedAddress value);

		@PlsqlRoutine("WHICH")
		String noVersion(byte[] value);

		String booleanOrText(boolean value);

		String booleanOrNumber(boolean value);

		String numberOrText(boolean value);

		String threeShapes(boolean value);

		String twoCrossed(boolean a, boolean b);

		String twoAligned(boolean a, boolean b);

		@PlsqlRoutine("BOOLEAN_OR_TEXT")
		String booleanOrTextWrapper(Boolean value);

		@PlsqlRoutine("NUMBER_OR_TEXT")
		String numberOrTextWrapper(Boolean value);

		@PlsqlRoutine("BOOLEAN_OR_TEXT")
		String booleanOrTextByString(String value);

		@PlsqlRoutine("NUMBER_OR_TEXT")
		String numberOrTextByString(String value);

		@PlsqlRoutine("THREE_SHAPES")
		String threeShapesByNumber(int value);

		@PlsqlRoutine("THREE_SHAPES")
		String threeShapesByString(String value);

		@PlsqlRoutine("BOOLEAN_OR_TEXT")
		String sNOrBoolean(@PlsqlBoolean(trueValue = "S", falseValue = "N") boolean value);

		@PlsqlRoutine("THREE_SHAPES")
		String sNToThreeShapes(@PlsqlBoolean(trueValue = "S", falseValue = "N") boolean value);

		@PlsqlRoutine("THREE_SHAPES")
		String numericToThreeShapes(@PlsqlBoolean(trueValue = "1", falseValue = "0") boolean value);

		@PlsqlRoutine("NUMBER_OR_TEXT")
		String numericToNumberOrText(@PlsqlBoolean(trueValue = "5", falseValue = "9") boolean value);

		@PlsqlRoutine("NUMBER_OR_TEXT")
		String sNToNumberOrText(@PlsqlBoolean(trueValue = "S", falseValue = "N") boolean value);
	}

	private final Overload overload = pkg(Overload.class);

	@Test
	@DisplayName("the Java type picks the version: number, text, date, OBJECT and collection")
	void by_type() {
		assertThat(overload.which(7)).isEqualTo("NUMBER");
		assertThat(overload.which("a")).isEqualTo("VARCHAR2");
		assertThat(overload.which(LocalDate.parse("2018-01-15"))).isEqualTo("DATE");
		assertThat(overload.which(new Address("Street A", 1))).isEqualTo("OBJECT");
		assertThat(overload.which(List.of("a"))).isEqualTo("COLLECTION");
	}

	@Test
	@DisplayName("the number of parameters picks the version")
	void by_parameter_count() {
		assertThat(overload.which(1, 2)).isEqualTo("TWO");
	}

	@Test
	@DisplayName("null goes to the version the Java signature picked")
	void with_null() {
		assertThat(overload.which((Integer) null)).isEqualTo("NUMBER");
		assertThat(overload.which((String) null)).isEqualTo("VARCHAR2");
		assertThat(overload.which((LocalDate) null)).isEqualTo("DATE");
		assertThat(overload.which((Address) null)).isEqualTo("OBJECT");
		assertThat(overload.which((List<String>) null)).isEqualTo("COLLECTION");
	}

	@Test
	@DisplayName("a parameter with DEFAULT counts toward both parameter counts")
	void with_default() {
		assertThat(overload.withDefaultText("a")).isEqualTo("VARCHAR2");
		assertThat(overload.withDefaultOmitted(1)).isEqualTo("NUMBER 1");
		assertThat(overload.withDefaultSupplied(1, 2)).isEqualTo("NUMBER 2");
	}

	@Test
	@DisplayName("no-argument version, for function and for procedure")
	void no_argument() {
		assertThat(overload.noArgument()).isEqualTo("ZERO");
		assertThat(overload.withArgument(1)).isEqualTo("ONE");
		overload.procNoArgument();
		overload.procWithArgument(1);
	}

	@Test
	@DisplayName("the method return type tells function from procedure")
	void function_or_procedure() {
		assertThat(overload.asFunction(1)).isEqualTo("FUNCTION");
		overload.asProcedure(1);
	}

	@Test
	@DisplayName("@PlsqlType tells apart two versions that take an OBJECT")
	void plsql_type_breaks_the_tie() {
		assertThat(overload.annotatedObject(new AnnotatedAddress("Street A", 1))).isEqualTo("ADDRESS");
	}

	@Test
	@DisplayName("more than one matching version is an error, so as not to call one of them by luck")
	void ambiguous() {
		assertThatThrownBy(() -> overload.sameJava(1))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("more than one version")
				.hasMessageContaining("SAME_JAVA");
		assertThatThrownBy(() -> overload.objectWithoutAnnotation(new Address("Street A", 1)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("more than one version")
				.hasMessageContaining("@PlsqlType");
	}

	@Test
	@DisplayName("a boolean goes to the most restrictive version: BOOLEAN, then NUMBER, then VARCHAR2")
	void boolean_prefers_the_most_restrictive() {
		assertThat(overload.booleanOrText(true)).isEqualTo("BOOLEAN");
		assertThat(overload.booleanOrNumber(true)).isEqualTo("BOOLEAN");
		assertThat(overload.threeShapes(true)).isEqualTo("BOOLEAN");
		assertThat(overload.numberOrText(true)).isEqualTo("NUMBER 1");
		assertThat(overload.numberOrText(false)).isEqualTo("NUMBER 0");
	}

	@Test
	@DisplayName("the wrapper follows the same order, and null goes to the chosen version")
	void boolean_wrapper() {
		assertThat(overload.booleanOrTextWrapper(true)).isEqualTo("BOOLEAN");
		assertThat(overload.booleanOrTextWrapper(null)).isEqualTo("BOOLEAN");
		assertThat(overload.numberOrTextWrapper(null)).isEqualTo("NUMBER ");
	}

	@Test
	@DisplayName("the other Java types still reach the permissive versions")
	void permissive_versions_remain_reachable() {
		assertThat(overload.booleanOrTextByString("Y")).isEqualTo("VARCHAR2");
		assertThat(overload.numberOrTextByString("Y")).isEqualTo("VARCHAR2 Y");
		assertThat(overload.threeShapesByNumber(1)).isEqualTo("NUMBER");
		assertThat(overload.threeShapesByString("a")).isEqualTo("VARCHAR2");
	}

	@Test
	@DisplayName("with several parameters, only a version that is best in all of them wins")
	void several_parameters() {
		assertThat(overload.twoAligned(true, true)).isEqualTo("BOOLEANS");
		assertThatThrownBy(() -> overload.twoCrossed(true, true))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("more than one version");
	}

	@Test
	@DisplayName("@PlsqlBoolean declares a literal: text or number beats BOOLEAN")
	void annotation_prefers_the_literal() {
		assertThat(overload.sNOrBoolean(true)).isEqualTo("VARCHAR2");
		assertThat(overload.sNToThreeShapes(true)).isEqualTo("VARCHAR2");
		assertThat(overload.numericToThreeShapes(true)).isEqualTo("NUMBER");
		assertThat(overload.numericToNumberOrText(false)).isEqualTo("NUMBER 9");
		assertThat(overload.sNToNumberOrText(true)).isEqualTo("VARCHAR2 S");
	}

	@Test
	@DisplayName("no matching version is an error that lists the existing ones")
	void none() {
		assertThatThrownBy(() -> overload.noVersion(new byte[] { 1 }))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("no version")
				.hasMessageContaining("P_VALUE VARCHAR2");
	}
}
