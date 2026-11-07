package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.convert.PlsqlEnumCodeConverter;
import io.github.rponte.penitent.exception.PlsqlMappingException;

class EnumByCodeTest extends OracleIntegrationTest {

	enum Status {
		ACTIVE("A"), INACTIVE("I");

		final String code;

		Status(String code) {
			this.code = code;
		}
	}

	enum Level {
		LOW(1), HIGH(2);

		final int code;

		Level(int code) {
			this.code = code;
		}
	}

	public static class StatusConverter extends PlsqlEnumCodeConverter<Status, String> {
		public StatusConverter() {
			super(Status.class, String.class, status -> status.code);
		}
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Scalars {
		@PlsqlRoutine("ECHO_TEXT")
		Status echo(Status value);

		@PlsqlRoutine("ECHO_TEXT")
		String asText(Status value);

		@PlsqlRoutine("ECHO_TEXT")
		Status fromText(String value);

		@PlsqlRoutine("ECHO_NUMBER")
		Level echoLevel(Level value);

		@PlsqlRoutine("ECHO_NUMBER")
		Integer levelAsNumber(Level value);
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		List<Status> echoNames(List<Status> value);
	}

	/** Without a registry: the annotation alone applies the converter. */
	@PlsqlPackage("FIXTURE_SCALARS")
	interface Annotated {
		@PlsqlRoutine("ECHO_TEXT")
		@PlsqlConvert(StatusConverter.class)
		Status echo(@PlsqlConvert(StatusConverter.class) Status value);
	}

	interface Blocks {
		/** Pads the code with spaces, as a CHAR(3) column would. */
		@PlsqlBlock("BEGIN :result := RPAD(:code, 3); END;")
		Status padded(String code);
	}

	private static final PlsqlConverters CODES = PlsqlConverters.of(
			PlsqlConverter.enumByCode(Status.class, String.class, status -> status.code),
			PlsqlConverter.enumByCode(Level.class, Integer.class, level -> level.code));

	private static <T> T withCodes(Class<T> type) {
		return PlsqlPackages.create(type, DATA_SOURCE, CODES);
	}

	@Test
	@DisplayName("the database receives the code, and the code comes back as the constant")
	void text_code() {
		Scalars scalars = withCodes(Scalars.class);

		assertThat(scalars.asText(Status.INACTIVE)).isEqualTo("I");
		assertThat(scalars.fromText("A")).isEqualTo(Status.ACTIVE);
		assertThat(scalars.echo(Status.ACTIVE)).isEqualTo(Status.ACTIVE);
	}

	@Test
	@DisplayName("a numeric code in a NUMBER parameter")
	void numeric_code() {
		Scalars scalars = withCodes(Scalars.class);

		assertThat(scalars.levelAsNumber(Level.HIGH)).isEqualTo(2);
		assertThat(scalars.echoLevel(Level.LOW)).isEqualTo(Level.LOW);
	}

	@Test
	@DisplayName("NULL stays null in both directions")
	void null_value() {
		assertThat(withCodes(Scalars.class).echo(null)).isNull();
	}

	@Test
	@DisplayName("per element in a collection")
	void collection() {
		assertThat(withCodes(Composites.class).echoNames(List.of(Status.ACTIVE, Status.INACTIVE)))
				.containsExactly(Status.ACTIVE, Status.INACTIVE);
	}

	@Test
	@DisplayName("with @PlsqlConvert, through a subclass with a no-arg constructor")
	void annotation() {
		assertThat(pkg(Annotated.class).echo(Status.INACTIVE)).isEqualTo(Status.INACTIVE);
	}

	@Test
	@DisplayName("a code padded by a CHAR column still matches")
	void char_padding() {
		assertThat(withCodes(Blocks.class).padded("A")).isEqualTo(Status.ACTIVE);
	}

	@Test
	@DisplayName("an unknown code read from the database is an error that names the converter, the value and the known codes")
	void unknown_code() {
		assertThatThrownBy(() -> withCodes(Scalars.class).fromText("X"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("the converter of Status failed to read 'X'")
				.hasMessageContaining("no Status has the code 'X'; known codes: [A, I]");
	}
}
