package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class DoubleConverterTest {

	private final DoubleConverter converter = new DoubleConverter();

	@Test
	@DisplayName("writes as is")
	void writes() {
		assertThat(converter.toDatabase(1.5d)).isEqualTo(1.5d);
	}

	@Test
	@DisplayName("reads from Number and from text")
	void reads() {
		assertThat(converter.fromDatabase(new BigDecimal("1.5"))).isEqualTo(1.5d);
		assertThat(converter.fromDatabase(1.5f)).isEqualTo(1.5d);
		assertThat(converter.fromDatabase("1.5")).isEqualTo(1.5d);
	}

	@Test
	@DisplayName("preserves NaN and infinity, which do not exist in BigDecimal")
	void non_numbers() {
		assertThat(converter.fromDatabase(Double.NaN)).isNaN();
		assertThat(converter.fromDatabase(Double.POSITIVE_INFINITY)).isEqualTo(Double.POSITIVE_INFINITY);
		assertThat(converter.fromDatabase(Double.NEGATIVE_INFINITY)).isEqualTo(Double.NEGATIVE_INFINITY);
	}

	@Test
	@DisplayName("non-numeric text and any other type are errors")
	void invalid() {
		assertThatThrownBy(() -> converter.fromDatabase("abc")).isInstanceOf(NumberFormatException.class);
		assertThatThrownBy(() -> converter.fromDatabase(Date.valueOf("2018-01-15")))
				.isInstanceOf(PlsqlMappingException.class);
	}

	@Test
	@DisplayName("through the codec: also works for double; null stays null, and in double it is an error")
	void through_codec() {
		assertThat(ScalarCodec.decode(1.5d, double.class, ValueConversion.DEFAULT, null)).isEqualTo(1.5d);
		assertThat(ScalarCodec.decode(null, Double.class, ValueConversion.DEFAULT, null)).isNull();
		assertThatThrownBy(() -> ScalarCodec.decode(null, double.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in double; declare Double");
	}
}
