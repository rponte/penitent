package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class ShortConverterTest {

	private final ShortConverter converter = new ShortConverter();

	@Test
	@DisplayName("writes as is, and reads from BigDecimal, another Number and text")
	void round_trip() {
		assertThat(converter.toDatabase((short) 7)).isEqualTo((short) 7);
		assertThat(converter.fromDatabase(new BigDecimal("7"))).isEqualTo((short) 7);
		assertThat(converter.fromDatabase(7)).isEqualTo((short) 7);
		assertThat(converter.fromDatabase("7")).isEqualTo((short) 7);
	}

	@Test
	@DisplayName("a fraction or an overflow fails instead of rounding; the codec translates the error")
	void does_not_fit() {
		assertThatThrownBy(() -> converter.fromDatabase(new BigDecimal("7.5"))).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> converter.fromDatabase(new BigDecimal("32768"))).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> ScalarCodec.decode(new BigDecimal("32768"), Short.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert '32768' to java.lang.Short");
	}

	@Test
	@DisplayName("through the codec: also for short; null stays null, and in short it is an error")
	void through_the_codec() {
		assertThat(ScalarCodec.decode(new BigDecimal("7"), short.class, ValueConversion.DEFAULT, null)).isEqualTo((short) 7);
		assertThat(ScalarCodec.decode(null, Short.class, ValueConversion.DEFAULT, null)).isNull();
		assertThatThrownBy(() -> ScalarCodec.decode(null, short.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in short; declare Short");
	}
}
