package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class InstantConverterTest {

	private static final Instant INSTANT = Instant.parse("2018-01-15T10:30:00Z");

	private final InstantConverter converter = new InstantConverter();

	@Test
	@DisplayName("writes in UTC, because the driver does not accept Instant")
	void writes() {
		assertThat(converter.toDatabase(INSTANT)).isEqualTo(OffsetDateTime.parse("2018-01-15T10:30:00Z"));
		assertThat(((OffsetDateTime) converter.toDatabase(INSTANT)).getOffset()).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	@DisplayName("reads the same instant, in any time zone")
	void reads() {
		OffsetDateTime inSaoPaulo = OffsetDateTime.parse("2018-01-15T07:30:00-03:00");

		assertThat(converter.fromDatabase(inSaoPaulo)).isEqualTo(INSTANT);
		assertThat(converter.fromDatabase(inSaoPaulo.toZonedDateTime())).isEqualTo(INSTANT);
	}

	@Test
	@DisplayName("a value without a time zone is refused, and the error mentions Instant")
	void without_time_zone() {
		assertThatThrownBy(() -> converter.fromDatabase(Timestamp.valueOf("2018-01-15 10:30:00")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("Instant would need a time zone");
	}

	@Test
	@DisplayName("any other type is an error")
	void other_type() {
		assertThatThrownBy(() -> converter.fromDatabase("x"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("to java.time.Instant");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, Instant.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
