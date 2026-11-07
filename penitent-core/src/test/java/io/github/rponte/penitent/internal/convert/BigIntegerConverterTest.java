package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class BigIntegerConverterTest {

	private final BigIntegerConverter converter = new BigIntegerConverter();

	@Test
	@DisplayName("writes as BigDecimal, which the driver accepts, and reads beyond the range of long")
	void round_trip() {
		BigInteger large = new BigInteger("123456789012345678901234567890");

		assertThat(converter.toDatabase(large)).isEqualTo(new BigDecimal(large));
		assertThat(converter.fromDatabase(new BigDecimal(large))).isEqualTo(large);
		assertThat(converter.fromDatabase(new BigDecimal("7.00"))).isEqualTo(BigInteger.valueOf(7));
	}

	@Test
	@DisplayName("a fraction fails instead of being truncated")
	void fraction() {
		assertThatThrownBy(() -> converter.fromDatabase(new BigDecimal("7.5"))).isInstanceOf(ArithmeticException.class);
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.encode(null, ValueConversion.DEFAULT)).isNull();
		assertThat(ScalarCodec.decode(null, BigInteger.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
