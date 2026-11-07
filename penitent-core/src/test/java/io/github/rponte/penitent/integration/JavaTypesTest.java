package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/** Java types beyond the basic ones in {@link ScalarsTest}, against NUMBER, VARCHAR2 and DATE. */
class JavaTypesTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Types {
		@PlsqlRoutine("ECHO_NUMBER")
		Object echoNumberAsObject(int value);

		@PlsqlRoutine("ECHO_TEXT")
		Object echoTextAsObject(Object value);

		@PlsqlRoutine("ECHO_DATE")
		LocalDateTime echoDateAsLocalDateTime(LocalDateTime value);

		@PlsqlRoutine("ECHO_DATE")
		java.sql.Date echoDateAsSqlDate(java.sql.Date value);

		@PlsqlRoutine("ECHO_NUMBER")
		Short echoShort(Short value);

		@PlsqlRoutine("ECHO_NUMBER")
		Float echoFloat(Float value);

		@PlsqlRoutine("ECHO_NUMBER")
		BigInteger echoBigInteger(BigInteger value);

		@PlsqlRoutine("ECHO_NUMBER")
		byte echoBytePrimitive(int value);

		@PlsqlRoutine("ECHO_NUMBER")
		short echoShortPrimitive(int value);

		@PlsqlRoutine("ECHO_NUMBER")
		float echoFloatPrimitive(int value);

		@PlsqlRoutine("ECHO_DATE")
		Date echoDateAsUtilDate(Date value);

		@PlsqlRoutine("ECHO_DATE")
		Timestamp echoDateAsTimestamp(Timestamp value);

		@PlsqlRoutine("ECHO_TEXT")
		char echoChar(char value);

		@PlsqlRoutine("ECHO_TEXT")
		UUID echoUuid(UUID value);

		@PlsqlRoutine("ECHO_TEXT")
		Optional<String> echoOptional(String value);

		@PlsqlRoutine("ECHO_NUMBER")
		Optional<Integer> echoOptionalNumber(Integer value);

		@PlsqlRoutine("ECHO_TEXT")
		String optionalParameter(Optional<String> value);

		@PlsqlRoutine("ECHO_TEXT")
		@SuppressWarnings("rawtypes")
		Optional rawOptional(String value);

		@PlsqlRoutine("ECHO_TEXT")
		char echoText(String value);

		@PlsqlRoutine("ECHO_NUMBER")
		BigInteger echoNumberAsBigInteger(BigDecimal value);
	}

	@PlsqlPackage("FIXTURE_TIME_ZONE")
	interface WithTimeZone {
		@PlsqlRoutine("ECHO_TSTZ")
		Date echoTstz(Date value);
	}

	private final Types types = pkg(Types.class);

	@Test
	@DisplayName("Object receives the value as the driver delivers it")
	void object() {
		assertThat(types.echoNumberAsObject(7)).isEqualTo(new BigDecimal("7"));
		assertThat(types.echoTextAsObject("a")).isEqualTo("a");
	}

	@Test
	@DisplayName("DATE keeps the time, and LocalDateTime does not lose it")
	void date_to_local_date_time() {
		LocalDateTime value = LocalDateTime.parse("2018-01-15T10:30:45");

		assertThat(types.echoDateAsLocalDateTime(value)).isEqualTo(value);
	}

	@Test
	@DisplayName("DATE becomes java.sql.Date")
	void date_to_sql_date() {
		java.sql.Date value = java.sql.Date.valueOf("2018-01-15");

		assertThat(types.echoDateAsSqlDate(value)).isEqualTo(value);
	}

	@Test
	@DisplayName("NUMBER becomes Short")
	void short_() {
		assertThat(types.echoShort((short) 7)).isEqualTo((short) 7);
	}

	@Test
	@DisplayName("NUMBER becomes Float")
	void float_() {
		assertThat(types.echoFloat(1.5f)).isEqualTo(1.5f);
	}

	@Test
	@DisplayName("NUMBER becomes BigInteger")
	void big_integer() {
		assertThat(types.echoBigInteger(BigInteger.TEN)).isEqualTo(BigInteger.TEN);
	}

	@Test
	@DisplayName("NUMBER becomes byte")
	void primitive_byte() {
		assertThat(types.echoBytePrimitive(7)).isEqualTo((byte) 7);
	}

	@Test
	@DisplayName("NUMBER becomes short")
	void primitive_short() {
		assertThat(types.echoShortPrimitive(7)).isEqualTo((short) 7);
	}

	@Test
	@DisplayName("NUMBER becomes float")
	void primitive_float() {
		assertThat(types.echoFloatPrimitive(7)).isEqualTo(7f);
	}

	@Test
	@DisplayName("DATE becomes java.util.Date")
	void util_date() {
		Date value = new Date(0);

		assertThat(types.echoDateAsUtilDate(value)).isEqualTo(value);
	}

	@Test
	@DisplayName("DATE becomes java.sql.Timestamp")
	void sql_timestamp() {
		Timestamp value = Timestamp.valueOf("2018-01-15 10:30:00");

		assertThat(types.echoDateAsTimestamp(value)).isEqualTo(value);
	}

	@Test
	@DisplayName("VARCHAR2 becomes char")
	void char_() {
		assertThat(types.echoChar('a')).isEqualTo('a');
	}

	@Test
	@DisplayName("VARCHAR2 becomes UUID")
	void uuid() {
		UUID value = UUID.randomUUID();

		assertThat(types.echoUuid(value)).isEqualTo(value);
	}

	@Test
	@DisplayName("VARCHAR2 becomes Optional")
	void optional() {
		assertThat(types.echoOptional("a")).contains("a");
	}

	@Test
	@DisplayName("Optional is empty when the database returns NULL, and holds the converted value otherwise")
	void optional_empty_and_converted() {
		assertThat(types.echoOptional(null)).isEmpty();
		assertThat(types.echoOptionalNumber(7)).contains(7);
	}

	@Test
	@DisplayName("Optional as a parameter, or without a concrete type, is an error before the call")
	void optional_rejected() {
		assertThatThrownBy(() -> types.optionalParameter(Optional.of("a")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("is Optional, which is only supported as a return type");
		assertThatThrownBy(() -> types.rawOptional("a"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("returns Optional without a concrete type");
	}

	@Test
	@DisplayName("char with more than one character, and BigInteger with a fraction, are errors instead of being cut")
	void does_not_fit() {
		assertThatThrownBy(() -> types.echoText("ab"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("'ab' does not fit in a char");
		assertThatThrownBy(() -> types.echoNumberAsBigInteger(new BigDecimal("7.5")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert '7.5' to java.math.BigInteger");
	}

	@Test
	@DisplayName("java.util.Date in a column with a time zone is rejected before the call, like LocalDateTime")
	void util_date_with_time_zone() {
		assertThatThrownBy(() -> pkg(WithTimeZone.class).echoTstz(new Date(0)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("Date has no time zone");
	}
}
