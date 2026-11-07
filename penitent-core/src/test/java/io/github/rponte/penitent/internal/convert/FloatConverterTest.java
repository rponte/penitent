package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class FloatConverterTest {

	private final FloatConverter converter = new FloatConverter();

	@Test
	@DisplayName("writes as is")
	void writes() {
		assertThat(converter.toDatabase(1.5f)).isEqualTo(1.5f);
	}

	@Test
	@DisplayName("reads from Number and from text")
	void reads() {
		assertThat(converter.fromDatabase(new BigDecimal("1.5"))).isEqualTo(1.5f);
		assertThat(converter.fromDatabase(1.5d)).isEqualTo(1.5f);
		assertThat(converter.fromDatabase("1.5")).isEqualTo(1.5f);
	}

	@Test
	@DisplayName("preserves NaN and infinity, which do not exist in BigDecimal")
	void non_numbers() {
		assertThat(converter.fromDatabase(Float.NaN)).isNaN();
		assertThat(converter.fromDatabase(Float.POSITIVE_INFINITY)).isEqualTo(Float.POSITIVE_INFINITY);
	}

	@Test
	@DisplayName("non-numeric text and any other type are errors")
	void invalid() {
		assertThatThrownBy(() -> converter.fromDatabase("abc")).isInstanceOf(NumberFormatException.class);
		assertThatThrownBy(() -> converter.fromDatabase(Date.valueOf("2018-01-15")))
				.isInstanceOf(PlsqlMappingException.class);
	}

	@Test
	@DisplayName("through the codec: also works for float; null stays null, and in float it is an error")
	void through_codec() {
		assertThat(ScalarCodec.decode(1.5f, float.class, ValueConversion.DEFAULT, null)).isEqualTo(1.5f);
		assertThat(ScalarCodec.decode(null, Float.class, ValueConversion.DEFAULT, null)).isNull();
		assertThatThrownBy(() -> ScalarCodec.decode(null, float.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in float; declare Float");
	}
}
