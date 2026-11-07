package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class ByteConverterTest {

	private final ByteConverter converter = new ByteConverter();

	@Test
	@DisplayName("writes as is, and reads from BigDecimal and another Number")
	void round_trip() {
		assertThat(converter.toDatabase((byte) 7)).isEqualTo((byte) 7);
		assertThat(converter.fromDatabase(new BigDecimal("-128"))).isEqualTo((byte) -128);
		assertThat(converter.fromDatabase(7)).isEqualTo((byte) 7);
	}

	@Test
	@DisplayName("an overflow fails instead of wrapping around")
	void does_not_fit() {
		assertThatThrownBy(() -> converter.fromDatabase(new BigDecimal("128"))).isInstanceOf(ArithmeticException.class);
	}

	@Test
	@DisplayName("through the codec: also for byte; null stays null, and in byte it is an error")
	void through_the_codec() {
		assertThat(ScalarCodec.decode(new BigDecimal("7"), byte.class, ValueConversion.DEFAULT, null)).isEqualTo((byte) 7);
		assertThat(ScalarCodec.decode(null, Byte.class, ValueConversion.DEFAULT, null)).isNull();
		assertThatThrownBy(() -> ScalarCodec.decode(null, byte.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in byte; declare Byte");
	}
}
