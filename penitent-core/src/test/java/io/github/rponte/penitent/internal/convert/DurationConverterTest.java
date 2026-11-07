package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;
import oracle.sql.INTERVALDS;

class DurationConverterTest {

	private static final Duration VALUE = Duration.ofDays(1).plusHours(2).plusMillis(345);

	private final DurationConverter converter = new DurationConverter();

	@Test
	@DisplayName("writes as the driver's INTERVALDS, and the codec opens it back into a Duration")
	void round_trip() {
		Object written = converter.toDatabase(VALUE);

		assertThat(written).isInstanceOf(INTERVALDS.class);
		assertThat(ScalarCodec.decode(written, Duration.class, ValueConversion.DEFAULT, null)).isEqualTo(VALUE);
	}

	@Test
	@DisplayName("another type is an error, and null stays null")
	void other_type_and_null() {
		assertThatThrownBy(() -> converter.fromDatabase("PT2H"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("cannot convert java.lang.String to java.time.Duration");
		assertThat(ScalarCodec.decode(null, Duration.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
