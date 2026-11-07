package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/** What a {@link PlsqlSettings} changes, and what an interface or a parameter declares over it. */
class SettingsTest extends OracleIntegrationTest {

	private static final LocalDate STARTS = LocalDate.parse("2018-01-01");
	private static final LocalDate ENDS = LocalDate.parse("2018-01-31");

	/** Declares no bind: it follows the settings. The Java order is the reverse of the routine's. */
	@PlsqlPackage("FIXTURE_NAMED")
	interface Undeclared {
		String period(LocalDate pEnd, LocalDate pStart);
	}

	@PlsqlPackage(value = "FIXTURE_NAMED", bind = PlsqlBind.BY_POSITION)
	interface DeclaredByPosition {
		String period(LocalDate pEnd, LocalDate pStart);
	}

	@PlsqlPackage(value = "FIXTURE_NAMED", bind = PlsqlBind.BY_NAME)
	interface DeclaredByName {
		String period(LocalDate pEnd, LocalDate pStart);
	}

	record Label(String value) {
	}

	record Shout(String value) {
	}

	static class ShoutConverter implements PlsqlConverter<Label, String> {
		@Override
		public String toDatabase(Label value) {
			return value.value().toUpperCase();
		}

		@Override
		public Label fromDatabase(String value) {
			return new Label(value);
		}
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Labels {
		Label echoText(Label value);

		@PlsqlRoutine("ECHO_TEXT")
		Label shouted(@PlsqlConvert(ShoutConverter.class) Label value);

		void raiseError(String message);
	}

	private static final PlsqlSettings BY_NAME = PlsqlSettings.defaults().withBind(PlsqlBind.BY_NAME);

	private static final PlsqlConverters LABELS = PlsqlConverters
			.of(PlsqlConverter.ofNullable(Label.class, String.class, l -> "[" + l.value() + "]", Label::new));

	@Test
	@DisplayName("without settings, an interface that declares no bind binds by position, as before")
	void default_is_by_position() {
		assertThat(pkg(Undeclared.class).period(ENDS, STARTS)).isEqualTo("2018-01-31..2018-01-01");
	}

	@Test
	@DisplayName("the settings' bind applies to an interface that declares none")
	void settings_bind() {
		assertThat(PlsqlPackages.create(Undeclared.class, DATA_SOURCE, BY_NAME).period(ENDS, STARTS))
				.isEqualTo("2018-01-01..2018-01-31");
	}

	@Test
	@DisplayName("the bind an interface declares wins over the settings, in both directions")
	void declared_bind_wins() {
		assertThat(PlsqlPackages.create(DeclaredByPosition.class, DATA_SOURCE, BY_NAME).period(ENDS, STARTS))
				.isEqualTo("2018-01-31..2018-01-01");
		assertThat(PlsqlPackages.create(DeclaredByName.class, DATA_SOURCE, PlsqlSettings.defaults())
				.period(ENDS, STARTS)).isEqualTo("2018-01-01..2018-01-31");
	}

	@Test
	@DisplayName("the settings' converters apply to every interface, and @PlsqlConvert wins over them")
	void settings_converters() {
		Labels labels = PlsqlPackages.create(Labels.class, DATA_SOURCE,
				PlsqlSettings.defaults().withConverters(LABELS));

		assertThat(labels.echoText(new Label("a"))).isEqualTo(new Label("[a]"));
		// the parameter goes through ShoutConverter, without brackets; the return, through the settings'
		assertThat(labels.shouted(new Label("a"))).isEqualTo(new Label("A"));
	}

	@Test
	@DisplayName("converters added with with() win over the settings' for the same type")
	void layered_converters() {
		PlsqlSettings global = PlsqlSettings.defaults().withConverters(LABELS);
		PlsqlConverters local = PlsqlConverters
				.of(PlsqlConverter.ofNullable(Label.class, String.class, l -> "<" + l.value() + ">", Label::new));

		Labels labels = PlsqlPackages.create(Labels.class, DATA_SOURCE,
				global.withConverters(global.converters().with(local)));

		assertThat(labels.echoText(new Label("a"))).isEqualTo(new Label("<a>"));
	}

	static final class Marked extends RuntimeException {
		Marked(Throwable cause) {
			super(cause);
		}
	}

	@Test
	@DisplayName("the settings' exception translator replaces the default one")
	void settings_translator() {
		Labels labels = PlsqlPackages.create(Labels.class, DATA_SOURCE,
				PlsqlSettings.defaults().withExceptionTranslator(failure -> new Marked(failure.cause())));

		assertThatThrownBy(() -> labels.raiseError("x")).isInstanceOf(Marked.class);
		assertThatThrownBy(() -> pkg(Labels.class).raiseError("x")).isInstanceOf(PlsqlRoutineRaisedException.class);
	}
}
