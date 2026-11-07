package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/** A value the database returns that the declared Java type cannot hold should say so, not leak. */
class InvalidConversionTest extends OracleIntegrationTest {

	enum Color {
		BLUE
	}

	@PlsqlPackage("FIXTURE_TYPES")
	interface Types {
		@PlsqlRoutine("TEXT_X")
		Boolean xAsBoolean();

		@PlsqlRoutine("TEXT_X")
		Color xAsEnum();

		@PlsqlRoutine("TEXT_X")
		Integer xAsInteger();

		@PlsqlRoutine("DECIMAL")
		Integer decimalAsInteger();

		@PlsqlRoutine("LARGE_NUMBER")
		Integer largeNumberAsInteger();
	}

	record ClobAsText(String text) {
	}

	record BlobAsText(String bytes) {
	}

	record ObjectAsText(String address) {
	}

	record EnumInsteadOfText(Color text) {
	}

	record StreetAsNumber(Integer street, Integer num) {
	}

	record Address(String street, Integer num) {
	}

	record NumberAsText(String street, String num) {
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		@PlsqlRoutine("ECHO_ADDRESS")
		StreetAsNumber streetAsNumber(Address value);

		@PlsqlRoutine("ECHO_ADDRESS")
		Address numberAsText(NumberAsText value);

		@PlsqlRoutine("ECHO_NUMBERS")
		List<java.math.BigDecimal> elementAsText(List<String> value);
	}

	public static class ReadOnly {
		public String getText() {
			return "value do getter";
		}
	}

	@PlsqlPackage("FIXTURE_CURSOR_LOB")
	interface Cursors {
		@PlsqlRoutine("COLUMNS_WITHOUT_TEXT")
		List<ClobAsText> clobAsText();

		@PlsqlRoutine("COLUMNS_WITHOUT_TEXT")
		List<BlobAsText> blobAsText();

		@PlsqlRoutine("COLUMNS_WITHOUT_TEXT")
		List<ObjectAsText> objectAsText();

		@PlsqlRoutine("COLUMNS_WITHOUT_TEXT")
		List<ReadOnly> columnWithoutSetter();

		@PlsqlRoutine("COLUMNS_WITHOUT_TEXT")
		List<EnumInsteadOfText> invalidColumn();
	}

	private final Types types = pkg(Types.class);
	private final Cursors cursors = pkg(Cursors.class);

	@Test
	@DisplayName("text that is neither S nor N, as Boolean")
	void text_as_boolean() {
		assertThatThrownBy(types::xAsBoolean)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("'X'")
				.hasMessageContaining("@PlsqlBoolean");
	}

	@Test
	@DisplayName("text outside the enum")
	void text_as_enum() {
		assertThatThrownBy(types::xAsEnum)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("FIXTURE_TYPES")
				.hasMessageContaining("'X'")
				.hasMessageContaining("Color");
	}

	@Test
	@DisplayName("text as number")
	void text_as_number() {
		assertThatThrownBy(types::xAsInteger)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("FIXTURE_TYPES")
				.hasMessageContaining("'X'")
				.hasMessageContaining("Integer");
	}

	@Test
	@DisplayName("decimal as Integer")
	void decimal_as_integer() {
		assertThatThrownBy(types::decimalAsInteger)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("FIXTURE_TYPES")
				.hasMessageContaining("'1.5'")
				.hasMessageContaining("Integer");
	}

	@Test
	@DisplayName("number that overflows Integer")
	void integer_overflow() {
		assertThatThrownBy(types::largeNumberAsInteger)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("FIXTURE_TYPES")
				.hasMessageContaining("'99999999999'")
				.hasMessageContaining("Integer");
	}

	@Test
	@DisplayName("a cursor CLOB column becomes the text, not the driver's toString")
	void clob_in_cursor() {
		assertThat(cursors.clobAsText()).containsExactly(new ClobAsText("abc"));
	}

	@Test
	@DisplayName("BLOB and OBJECT have no text: it is an error, not the driver's toString")
	void no_text_in_cursor() {
		assertThatThrownBy(cursors::blobAsText)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("to String");
		assertThatThrownBy(cursors::objectAsText)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("to String");
	}

	@Test
	@DisplayName("a cursor column matching a property without a setter is an error, not a discarded value")
	void cursor_without_setter() {
		assertThatThrownBy(cursors::columnWithoutSetter)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("ReadOnly.text");
	}

	@Test
	@DisplayName("an invalid value in an attribute names the attribute and the routine, and keeps the cause")
	void invalid_attribute() {
		assertThatThrownBy(() -> pkg(Composites.class).streetAsNumber(new Address("Street A", 1)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("ECHO_ADDRESS")
				.hasMessageContaining("attribute STREET")
				.hasMessageContaining("'Street A'")
				.hasRootCauseInstanceOf(NumberFormatException.class);
	}

	@Test
	@DisplayName("an invalid value in a cursor column names the column")
	void invalid_column() {
		assertThatThrownBy(cursors::invalidColumn)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("COLUMNS_WITHOUT_TEXT")
				.hasMessageContaining("column TEXT");
	}

	@Test
	@DisplayName("a value that does not fit in the attribute or element says so, and does not leak a driver exception")
	void value_that_does_not_fit() {
		Composites composites = pkg(Composites.class);

		assertThatThrownBy(() -> composites.numberAsText(new NumberAsText("Street A", "abc")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("a value does not fit in")
				.hasMessageContaining("ADDRESS_T")
				.hasMessageContaining("NUM=String");
		assertThatThrownBy(() -> composites.elementAsText(List.of("abc")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("an element does not fit in");
	}
}
