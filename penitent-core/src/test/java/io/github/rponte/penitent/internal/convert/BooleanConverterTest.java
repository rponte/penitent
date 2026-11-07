package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class BooleanConverterTest {

	private final BooleanConverter standard = new BooleanConverter(ValueConversion.DEFAULT);
	private final BooleanConverter sn = new BooleanConverter(new ValueConversion("S", "N"));

	@Test
	@DisplayName("writes the given literal")
	void writes() {
		assertThat(standard.toDatabase(true)).isEqualTo("Y");
		assertThat(standard.toDatabase(false)).isEqualTo("N");
		assertThat(sn.toDatabase(true)).isEqualTo("S");
		assertThat(sn.toDatabase(false)).isEqualTo("N");
	}

	@Test
	@DisplayName("reads the literal in any case, ignoring spaces")
	void reads_text() {
		assertThat(sn.fromDatabase("S")).isTrue();
		assertThat(sn.fromDatabase("s ")).isTrue();
		assertThat(sn.fromDatabase("N")).isFalse();
	}

	@Test
	@DisplayName("with no declared literals, a number is 1 or 0, at any scale")
	void reads_number() {
		assertThat(standard.fromDatabase(new BigDecimal("1"))).isTrue();
		assertThat(standard.fromDatabase(new BigDecimal("1.00"))).isTrue();
		assertThat(standard.fromDatabase(new BigDecimal("0.0"))).isFalse();
		assertThat(standard.fromDatabase(0)).isFalse();
	}

	@Test
	@DisplayName("with declared numeric literals, compares against the number without scientific notation")
	void numeric_literals() {
		BooleanConverter tenTwenty = new BooleanConverter(new ValueConversion("10", "20"));

		assertThat(tenTwenty.fromDatabase(new BigDecimal("10"))).isTrue();
		assertThat(tenTwenty.fromDatabase(new BigDecimal("20.0"))).isFalse();
	}

	@Test
	@DisplayName("a real BOOLEAN passes straight through, without looking at the literals")
	void native_boolean() {
		assertThat(sn.fromDatabase(Boolean.TRUE)).isTrue();
		assertThat(sn.fromDatabase(Boolean.FALSE)).isFalse();
	}

	@Test
	@DisplayName("anything that is neither literal is an error, not false")
	void unknown_literal() {
		assertThatThrownBy(() -> standard.fromDatabase("S"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert 'S'")
				.hasMessageContaining("expected 'Y' or 'N'")
				.hasMessageContaining("@PlsqlBoolean");
		assertThatThrownBy(() -> standard.fromDatabase(new BigDecimal("2")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("expected '1' or '0'");
		assertThatThrownBy(() -> sn.fromDatabase(new BigDecimal("1")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("expected 'S' or 'N'");
	}

	@Test
	@DisplayName("through the codec: null stays null, and in a primitive it is an error")
	void null_value() {
		assertThat(ScalarCodec.encode(null, ValueConversion.DEFAULT)).isNull();
		assertThat(ScalarCodec.decode(null, Boolean.class, ValueConversion.DEFAULT, null)).isNull();
		assertThatThrownBy(() -> ScalarCodec.decode(null, boolean.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in boolean; declare Boolean");
	}
}
