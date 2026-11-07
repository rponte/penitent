package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class OffsetDateTimeConverterTest {

	private static final OffsetDateTime VALUE = OffsetDateTime.parse("2018-01-15T07:30:00-03:00");

	private final OffsetDateTimeConverter converter = new OffsetDateTimeConverter();

	@Test
	@DisplayName("writes as is")
	void writes() {
		assertThat(converter.toDatabase(VALUE)).isSameAs(VALUE);
	}

	@Test
	@DisplayName("reads from OffsetDateTime and from ZonedDateTime, keeping the offset")
	void reads() {
		assertThat(converter.fromDatabase(VALUE)).isEqualTo(VALUE);
		assertThat(converter.fromDatabase(VALUE.toZonedDateTime())).isEqualTo(VALUE);
		assertThat(converter.fromDatabase(ZonedDateTime.parse("2018-01-15T07:30:00-03:00[America/Sao_Paulo]")))
				.isEqualTo(VALUE);
	}

	@Test
	@DisplayName("a value without a time zone is refused, and the error mentions OffsetDateTime")
	void without_time_zone() {
		assertThatThrownBy(() -> converter.fromDatabase(Timestamp.valueOf("2018-01-15 10:30:00")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("OffsetDateTime would need a time zone");
	}

	@Test
	@DisplayName("any other type is an error")
	void other_type() {
		assertThatThrownBy(() -> converter.fromDatabase("x"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("to java.time.OffsetDateTime");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, OffsetDateTime.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
