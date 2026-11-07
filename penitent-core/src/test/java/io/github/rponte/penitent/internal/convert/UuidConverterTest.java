package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class UuidConverterTest {

	private static final UUID VALUE = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

	private final UuidConverter converter = new UuidConverter();

	@Test
	@DisplayName("writes the 36-character text and reads it back, ignoring the padding of a CHAR")
	void round_trip() {
		assertThat(converter.toDatabase(VALUE)).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
		assertThat(converter.fromDatabase("123e4567-e89b-12d3-a456-426614174000")).isEqualTo(VALUE);
		assertThat(converter.fromDatabase("123e4567-e89b-12d3-a456-426614174000    ")).isEqualTo(VALUE);
	}

	@Test
	@DisplayName("a text that is not a UUID fails; the codec translates the error")
	void invalid() {
		assertThatThrownBy(() -> converter.fromDatabase("abc")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ScalarCodec.decode("abc", UUID.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert 'abc' to java.util.UUID");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, UUID.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
