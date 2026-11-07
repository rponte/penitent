package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class LongConverterTest {

	private final LongConverter converter = new LongConverter();

	@Test
	@DisplayName("writes as is")
	void writes() {
		assertThat(converter.toDatabase(7L)).isEqualTo(7L);
	}

	@Test
	@DisplayName("reads from BigDecimal, from another Number and from text, beyond the int range")
	void reads() {
		assertThat(converter.fromDatabase(new BigDecimal("2147483648"))).isEqualTo(2147483648L);
		assertThat(converter.fromDatabase(7)).isEqualTo(7L);
		assertThat(converter.fromDatabase("7")).isEqualTo(7L);
	}

	@Test
	@DisplayName("fractions and overflow fail instead of rounding; the codec translates the error")
	void does_not_fit() {
		assertThatThrownBy(() -> converter.fromDatabase(new BigDecimal("7.5"))).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> converter.fromDatabase(new BigDecimal("9223372036854775808")))
				.isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> ScalarCodec.decode(new BigDecimal("7.5"), Long.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert '7.5' to java.lang.Long");
	}

	@Test
	@DisplayName("through the codec: also works for long; null stays null, and in long it is an error")
	void through_codec() {
		assertThat(ScalarCodec.decode(new BigDecimal("7"), long.class, ValueConversion.DEFAULT, null)).isEqualTo(7L);
		assertThat(ScalarCodec.decode(null, Long.class, ValueConversion.DEFAULT, null)).isNull();
		assertThatThrownBy(() -> ScalarCodec.decode(null, long.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in long; declare Long");
	}
}
