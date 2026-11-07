package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Date;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class BigDecimalConverterTest {

	private final BigDecimalConverter converter = new BigDecimalConverter();

	@Test
	@DisplayName("writes as is")
	void writes() {
		BigDecimal value = new BigDecimal("7.50");

		assertThat(converter.toDatabase(value)).isSameAs(value);
	}

	@Test
	@DisplayName("reads from BigDecimal without changing the scale, from another Number and from text")
	void reads() {
		BigDecimal value = new BigDecimal("7.50");

		assertThat(converter.fromDatabase(value)).isSameAs(value);
		assertThat(converter.fromDatabase(7)).isEqualTo(new BigDecimal("7"));
		assertThat(converter.fromDatabase(1.5d)).isEqualTo(new BigDecimal("1.5"));
		assertThat(converter.fromDatabase(" 7.50 ")).isEqualTo(value);
	}

	@Test
	@DisplayName("non-numeric text and NaN fail; the codec translates the error")
	void invalid() {
		assertThatThrownBy(() -> converter.fromDatabase("abc")).isInstanceOf(NumberFormatException.class);
		assertThatThrownBy(() -> converter.fromDatabase(Double.NaN)).isInstanceOf(NumberFormatException.class);
		assertThatThrownBy(() -> ScalarCodec.decode("abc", BigDecimal.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert 'abc'");
	}

	@Test
	@DisplayName("any other type is an error")
	void other_type() {
		assertThatThrownBy(() -> converter.fromDatabase(Date.valueOf("2018-01-15")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("to BigDecimal");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, BigDecimal.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
