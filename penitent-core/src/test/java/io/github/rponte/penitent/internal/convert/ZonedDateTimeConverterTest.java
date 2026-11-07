package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class ZonedDateTimeConverterTest {

	private static final ZonedDateTime WITH_REGION = ZonedDateTime.parse("2018-01-15T07:30:00-03:00[America/Sao_Paulo]");

	private final ZonedDateTimeConverter converter = new ZonedDateTimeConverter();

	@Test
	@DisplayName("writes as is")
	void writes() {
		assertThat(converter.toDatabase(WITH_REGION)).isSameAs(WITH_REGION);
	}

	@Test
	@DisplayName("reads a ZonedDateTime without losing the region, and an OffsetDateTime by its offset")
	void reads() {
		assertThat(converter.fromDatabase(WITH_REGION)).isSameAs(WITH_REGION);

		OffsetDateTime withOffset = WITH_REGION.toOffsetDateTime();
		assertThat(converter.fromDatabase(withOffset)).isEqualTo(withOffset.toZonedDateTime());
	}

	@Test
	@DisplayName("a value without a time zone is refused, because the time zone would be invented")
	void without_time_zone() {
		assertThatThrownBy(() -> converter.fromDatabase(Timestamp.valueOf("2018-01-15 10:30:00")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("ZonedDateTime would need a time zone");
		assertThatThrownBy(() -> converter.fromDatabase(Date.valueOf("2018-01-15")))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("ZonedDateTime would need a time zone");
	}

	@Test
	@DisplayName("any other type is an error")
	void other_type() {
		assertThatThrownBy(() -> converter.fromDatabase("2018-01-15"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("cannot convert java.lang.String to java.time.ZonedDateTime");
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, ZonedDateTime.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
