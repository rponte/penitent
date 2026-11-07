package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class IntegerConverterTest {

	private final IntegerConverter converter = new IntegerConverter();

	@Test
	@DisplayName("writes as is")
	void writes() {
		assertThat(converter.toDatabase(7)).isEqualTo(7);
	}

	@Test
	@DisplayName("reads from BigDecimal, from another Number and from text")
	void reads() {
		assertThat(converter.fromDatabase(new BigDecimal("7"))).isEqualTo(7);
		assertThat(converter.fromDatabase(new BigDecimal("7.00"))).isEqualTo(7);
		assertThat(converter.fromDatabase(7L)).isEqualTo(7);
		assertThat(converter.fromDatabase("7")).isEqualTo(7);
	}

	@Test
	@DisplayName("fractions and overflow fail instead of rounding; the codec translates the error")
	void does_not_fit() {
		assertThatThrownBy(() -> converter.fromDatabase(new BigDecimal("7.5"))).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> converter.fromDatabase(new BigDecimal("2147483648")))
				.isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> ScalarCodec.decode(new BigDecimal("7.5"), Integer.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert '7.5' to java.lang.Integer");
	}

	@Test
	@DisplayName("through the codec: also works for int; null stays null, and in int it is an error")
	void through_codec() {
		assertThat(ScalarCodec.decode(new BigDecimal("7"), int.class, ValueConversion.DEFAULT, null)).isEqualTo(7);
		assertThat(ScalarCodec.decode(null, Integer.class, ValueConversion.DEFAULT, null)).isNull();
		assertThatThrownBy(() -> ScalarCodec.decode(null, int.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in int; declare Integer");
	}
}
