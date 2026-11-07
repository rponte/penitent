package io.github.rponte.penitent.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.AppliedConverter;

/** The enum-by-code converter without a database. */
class PlsqlEnumCodeConverterTest {

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

	enum Duplicated {
		ONE("X"), TWO("X");

		final String code;

		Duplicated(String code) {
			this.code = code;
		}
	}

	enum WithoutCode {
		ONE("1"), TWO(null);

		final String code;

		WithoutCode(String code) {
			this.code = code;
		}
	}

	private final PlsqlConverter<Status, String> status = PlsqlConverter.enumByCode(Status.class, String.class,
			s -> s.code);

	@Test
	@DisplayName("writes the code and reads it back")
	void round_trip() {
		assertThat(status.toDatabase(Status.ACTIVE)).isEqualTo("A");
		assertThat(status.fromDatabase("I")).isEqualTo(Status.INACTIVE);
	}

	@Test
	@DisplayName("null stays null in both directions")
	void null_value() {
		assertThat(status.toDatabase(null)).isNull();
		assertThat(status.fromDatabase(null)).isNull();
	}

	@Test
	@DisplayName("an unknown code is an error that lists the known codes")
	void unknown_code() {
		assertThatThrownBy(() -> status.fromDatabase("X"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("no Status has the code 'X'; known codes: [A, I]");
	}

	@Test
	@DisplayName("a text code ignores trailing spaces, as in a CHAR column, but not leading ones or case")
	void char_padding() {
		assertThat(status.fromDatabase("A  ")).isEqualTo(Status.ACTIVE);
		assertThatThrownBy(() -> status.fromDatabase(" A")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> status.fromDatabase("a")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("a numeric code: Integer, and BigDecimal ignoring its scale")
	void numeric_codes() {
		PlsqlConverter<Level, Integer> integer = PlsqlConverter.enumByCode(Level.class, Integer.class, l -> l.code);
		PlsqlConverter<Level, BigDecimal> decimal = PlsqlConverter.enumByCode(Level.class, BigDecimal.class,
				l -> BigDecimal.valueOf(l.code));

		assertThat(integer.toDatabase(Level.HIGH)).isEqualTo(2);
		assertThat(integer.fromDatabase(1)).isEqualTo(Level.LOW);
		assertThat(decimal.fromDatabase(new BigDecimal("2.00"))).isEqualTo(Level.HIGH);
	}

	@Test
	@DisplayName("two constants with the same code fail when the converter is created")
	void duplicated_code() {
		assertThatThrownBy(() -> PlsqlConverter.enumByCode(Duplicated.class, String.class, d -> d.code))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("Duplicated.ONE and Duplicated.TWO share the code 'X'");
	}

	@Test
	@DisplayName("a constant without a code fails when the converter is created")
	void missing_code() {
		assertThatThrownBy(() -> PlsqlConverter.enumByCode(WithoutCode.class, String.class, w -> w.code))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("WithoutCode.TWO has no code");
	}

	@Test
	@DisplayName("the registry reads its types, both from the factory and from a subclass")
	void declared_types() {
		class StatusConverter extends PlsqlEnumCodeConverter<Status, String> {
			StatusConverter() {
				super(Status.class, String.class, s -> s.code);
			}
		}
		AppliedConverter fromFactory = PlsqlConverters.of(status).find(null, Status.class, null, "x");
		AppliedConverter fromSubclass = PlsqlConverters.of(new StatusConverter()).find(null, Status.class, null, "x");

		assertThat(fromFactory.databaseType()).isEqualTo(String.class);
		assertThat(fromSubclass.databaseType()).isEqualTo(String.class);
	}
}
