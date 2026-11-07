package io.github.rponte.penitent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlExceptionTranslator;

class PlsqlSettingsTest {

	record Code(String value) {
	}

	@Test
	@DisplayName("the defaults: no converters, by position, and the creator's translator")
	void defaults() {
		PlsqlSettings defaults = PlsqlSettings.defaults();

		assertThat(defaults.bind()).isEqualTo(PlsqlBind.BY_POSITION);
		assertThat(defaults.exceptionTranslator()).isEmpty();
		assertThat(defaults.converters()).hasToString("[]");
	}

	@Test
	@DisplayName("each with returns a new object, and the original does not change")
	void immutable() {
		PlsqlSettings defaults = PlsqlSettings.defaults();
		PlsqlExceptionTranslator translator = failure -> new IllegalStateException();
		PlsqlConverters converters = PlsqlConverters
				.of(PlsqlConverter.ofNullable(Code.class, String.class, Code::value, Code::new));

		PlsqlSettings changed = defaults.withBind(PlsqlBind.BY_NAME)
				.withExceptionTranslator(translator)
				.withConverters(converters);

		assertThat(changed.bind()).isEqualTo(PlsqlBind.BY_NAME);
		assertThat(changed.exceptionTranslator()).containsSame(translator);
		assertThat(changed.converters()).isSameAs(converters);
		assertThat(defaults.bind()).isEqualTo(PlsqlBind.BY_POSITION);
		assertThat(defaults.exceptionTranslator()).isEmpty();
		assertThat(PlsqlSettings.defaults()).isSameAs(defaults);
	}

	@Test
	@DisplayName("DEFAULT is not a setting: it is what the annotations use to defer to the settings")
	void default_bind_is_rejected() {
		assertThatThrownBy(() -> PlsqlSettings.defaults().withBind(PlsqlBind.DEFAULT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("BY_POSITION or BY_NAME");
	}

	@Test
	@DisplayName("null is rejected with the name of what is missing")
	void nulls() {
		PlsqlSettings defaults = PlsqlSettings.defaults();

		assertThatThrownBy(() -> defaults.withBind(null)).hasMessage("bind");
		assertThatThrownBy(() -> defaults.withConverters(null)).hasMessage("converters");
		assertThatThrownBy(() -> defaults.withExceptionTranslator(null)).hasMessage("exceptionTranslator");
	}

	@Test
	@DisplayName("the annotations default to DEFAULT, so an interface that does not choose follows the settings")
	void annotations_default_to_settings() throws NoSuchMethodException {
		assertThat(PlsqlPackage.class.getMethod("bind").getDefaultValue()).isEqualTo(PlsqlBind.DEFAULT);
		assertThat(PlsqlSchema.class.getMethod("bind").getDefaultValue()).isEqualTo(PlsqlBind.DEFAULT);
	}
}
