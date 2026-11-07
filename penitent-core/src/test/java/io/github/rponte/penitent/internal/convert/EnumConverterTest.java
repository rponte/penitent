package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class EnumConverterTest {

	enum Status {
		NEW,
		WITH_BODY {
			@Override
			public String toString() {
				return "other text";
			}
		}
	}

	private final EnumConverter<Status> converter = new EnumConverter<>(Status.class);

	@Test
	@DisplayName("writes name(), not toString()")
	void writes() {
		assertThat(converter.toDatabase(Status.NEW)).isEqualTo("NEW");
		assertThat(converter.toDatabase(Status.WITH_BODY)).isEqualTo("WITH_BODY");
	}

	@Test
	@DisplayName("reads by name, ignoring the padding of a CHAR")
	void reads() {
		assertThat(converter.fromDatabase("NEW")).isEqualTo(Status.NEW);
		assertThat(converter.fromDatabase("NEW   ")).isEqualTo(Status.NEW);
	}

	@Test
	@DisplayName("an unknown name, or one in a different case, is an error")
	void unknown_name() {
		assertThatThrownBy(() -> converter.fromDatabase("X")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> converter.fromDatabase("new")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("through the codec: constant with its own body, translated error and null")
	void through_codec() {
		assertThat(ScalarCodec.encode(Status.WITH_BODY, ValueConversion.DEFAULT)).isEqualTo("WITH_BODY");
		assertThat(ScalarCodec.decode(null, Status.class, ValueConversion.DEFAULT, null)).isNull();
		assertThatThrownBy(() -> ScalarCodec.decode("X", Status.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert 'X'");
	}
}
