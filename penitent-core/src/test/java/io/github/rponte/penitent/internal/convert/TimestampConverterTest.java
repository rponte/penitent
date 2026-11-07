package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class TimestampConverterTest {

	private static final Timestamp VALUE = Timestamp.valueOf("2018-01-15 10:30:45.123");

	private final TimestampConverter converter = new TimestampConverter();

	@Test
	@DisplayName("writes as is, and reads from Timestamp and java.sql.Date")
	void round_trip() {
		assertThat(converter.toDatabase(VALUE)).isSameAs(VALUE);
		assertThat(converter.fromDatabase(VALUE)).isSameAs(VALUE);
		assertThat(converter.fromDatabase(new java.sql.Date(VALUE.getTime()))).isEqualTo(new Timestamp(VALUE.getTime()));
	}

	@Test
	@DisplayName("a value with a time zone and another type are errors")
	void rejected() {
		assertThatThrownBy(() -> converter.fromDatabase(ZonedDateTime.now()))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("Timestamp has no time zone");
		assertThatThrownBy(() -> converter.fromDatabase("x"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("to java.sql.Timestamp");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, Timestamp.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
