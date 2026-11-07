package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;
import oracle.sql.TIMESTAMP;

/** What comes before the converters: choosing which one to use, {@code null} and the driver's types. */
class ScalarCodecTest {

	enum Status {
		NEW
	}

	@PlsqlBoolean(trueValue = "S", falseValue = "N")
	private static boolean annotated;

	private static Object write(Object value) {
		return ScalarCodec.encode(value, ValueConversion.DEFAULT);
	}

	private static Object read(Object raw, Class<?> target) {
		return ScalarCodec.decode(raw, target, ValueConversion.DEFAULT, null);
	}

	@Test
	@DisplayName("writes according to the type of the value")
	void choice_on_write() {
		Instant instant = Instant.parse("2018-01-15T10:30:00Z");

		assertThat(write(Status.NEW)).isEqualTo("NEW");
		assertThat(write(true)).isEqualTo("Y");
		assertThat(write(instant)).isEqualTo(instant.atOffset(ZoneOffset.UTC));
		assertThat(write(LocalDate.parse("2018-01-15"))).isInstanceOf(java.sql.Date.class);
		assertThat(write(LocalDateTime.parse("2018-01-15T10:30:00"))).isInstanceOf(Timestamp.class);
	}

	@Test
	@DisplayName("reads according to the declared type")
	void choice_on_read() {
		assertThat(read("NEW", Status.class)).isEqualTo(Status.NEW);
		assertThat(read("Y", Boolean.class)).isEqualTo(true);
		assertThat(read("Y", boolean.class)).isEqualTo(true);
		assertThat(read(new BigDecimal("7"), Integer.class)).isEqualTo(7);
		assertThat(read(new BigDecimal("7"), String.class)).isEqualTo("7");
	}

	@Test
	@DisplayName("uses the given literals")
	void literals() {
		ValueConversion sn = new ValueConversion("S", "N");

		assertThat(ScalarCodec.encode(true, sn)).isEqualTo("S");
		assertThat(ScalarCodec.decode("S", Boolean.class, sn, null)).isEqualTo(true);
	}

	@Test
	@DisplayName("null stays null in both directions, even for a type it does not know")
	void null_value() {
		assertThat(write(null)).isNull();
		assertThat(read(null, String.class)).isNull();
		assertThat(read(null, StringBuilder.class)).isNull();
	}

	@Test
	@DisplayName("null in a primitive is an error, and says what to declare")
	void null_in_primitive() {
		assertThatThrownBy(() -> read(null, short.class))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in short; declare Short");
	}

	@Test
	@DisplayName("unknown type: goes as is, and only comes back if the driver returns that class")
	void unknown_type() {
		Object value = new StringBuilder("x");

		assertThat(write(value)).isSameAs(value);
		assertThat(read(value, StringBuilder.class)).isSameAs(value);
		assertThat(read(value, CharSequence.class)).isSameAs(value);
		assertThatThrownBy(() -> read("x", StringBuilder.class))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert java.lang.String to java.lang.StringBuilder");
	}

	@Test
	@DisplayName("a format or range error comes out as PlsqlMappingException, with the cause")
	void translated_errors() {
		assertThatThrownBy(() -> read("abc", Integer.class))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert 'abc' to java.lang.Integer")
				.hasCauseInstanceOf(NumberFormatException.class);
		assertThatThrownBy(() -> read(new BigDecimal("7.5"), Integer.class))
				.isInstanceOf(PlsqlMappingException.class)
				.hasCauseInstanceOf(ArithmeticException.class);
	}

	@Test
	@DisplayName("unwraps the driver's TIMESTAMP before converting")
	void driver_timestamp() {
		Timestamp moment = Timestamp.valueOf("2018-01-15 10:30:00");

		assertThat(read(new TIMESTAMP(moment), LocalDateTime.class)).isEqualTo(moment.toLocalDateTime());
	}

	@Test
	@DisplayName("refuses what would lose or invent the time zone")
	void time_zone_refusals() {
		String withTimeZone = "TIMESTAMP(6) WITH TIME ZONE";

		assertThat(ScalarCodec.temporalRefusal(withTimeZone, LocalDateTime.class)).contains("has no time zone");
		assertThat(ScalarCodec.temporalRefusal(withTimeZone, LocalDate.class)).contains("has no time zone");
		assertThat(ScalarCodec.temporalRefusal(withTimeZone, java.util.Date.class)).contains("has no time zone");
		assertThat(ScalarCodec.temporalRefusal(withTimeZone, java.sql.Date.class)).contains("has no time zone");
		assertThat(ScalarCodec.temporalRefusal(withTimeZone, java.sql.Timestamp.class)).contains("has no time zone");
		assertThat(ScalarCodec.temporalRefusal("TIMESTAMP WITH LOCAL TIME ZONE", LocalDateTime.class))
				.contains("has no time zone");
		assertThat(ScalarCodec.temporalRefusal("DATE", Instant.class)).contains("would need a time zone");
		assertThat(ScalarCodec.temporalRefusal("TIMESTAMP(6)", OffsetDateTime.class)).contains("would lose the time zone");
		assertThat(ScalarCodec.temporalRefusal("DATE", ZonedDateTime.class)).contains("would lose the time zone");
	}

	@Test
	@DisplayName("accepts what does not lose the time zone, and has no opinion on other types")
	void no_time_zone_refusal() {
		String withTimeZone = "TIMESTAMP WITH TIME ZONE";

		assertThat(ScalarCodec.temporalRefusal(withTimeZone, OffsetDateTime.class)).isNull();
		assertThat(ScalarCodec.temporalRefusal(withTimeZone, ZonedDateTime.class)).isNull();
		assertThat(ScalarCodec.temporalRefusal(withTimeZone, Instant.class)).isNull();
		assertThat(ScalarCodec.temporalRefusal("DATE", LocalDate.class)).isNull();
		assertThat(ScalarCodec.temporalRefusal("TIMESTAMP(6)", LocalDateTime.class)).isNull();
		assertThat(ScalarCodec.temporalRefusal("VARCHAR2", Instant.class)).isNull();
	}

	@Test
	@DisplayName("ValueConversion: without the annotation it is the default, and with it uses the literals")
	void value_conversion() throws Exception {
		PlsqlBoolean annotation = ScalarCodecTest.class.getDeclaredField("annotated").getAnnotation(PlsqlBoolean.class);

		assertThat(ValueConversion.from(null)).isSameAs(ValueConversion.DEFAULT);
		assertThat(ValueConversion.from(annotation)).isEqualTo(new ValueConversion("S", "N"));
	}
}
