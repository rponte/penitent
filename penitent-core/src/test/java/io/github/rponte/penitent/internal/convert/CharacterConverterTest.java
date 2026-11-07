package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class CharacterConverterTest {

	private final CharacterConverter converter = new CharacterConverter();

	@Test
	@DisplayName("writes as a one-character text and reads it back")
	void round_trip() {
		assertThat(converter.toDatabase('a')).isEqualTo("a");
		assertThat(converter.fromDatabase("a")).isEqualTo('a');
	}

	@Test
	@DisplayName("a text with more than one character is an error, instead of being cut")
	void longer_text() {
		assertThatThrownBy(() -> converter.fromDatabase("ab"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("'ab' does not fit in a char: it has 2 characters");
	}

	@Test
	@DisplayName("through the codec: also for char; null stays null, and in char it is an error")
	void through_the_codec() {
		assertThat(ScalarCodec.decode("a", char.class, ValueConversion.DEFAULT, null)).isEqualTo('a');
		assertThat(ScalarCodec.decode(null, Character.class, ValueConversion.DEFAULT, null)).isNull();
		assertThatThrownBy(() -> ScalarCodec.decode(null, char.class, ValueConversion.DEFAULT, null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in char; declare Character");
	}
}
