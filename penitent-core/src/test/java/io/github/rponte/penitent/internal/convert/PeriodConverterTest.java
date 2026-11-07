package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Period;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;
import oracle.sql.INTERVALYM;

class PeriodConverterTest {

	private final PeriodConverter converter = new PeriodConverter();

	@Test
	@DisplayName("writes as the driver's INTERVALYM, and the codec opens it back into a Period")
	void round_trip() {
		Object written = converter.toDatabase(Period.of(2, 3, 0));

		assertThat(written).isInstanceOf(INTERVALYM.class);
		assertThat(ScalarCodec.decode(written, Period.class, ValueConversion.DEFAULT, null)).isEqualTo(Period.of(2, 3, 0));
	}

	@Test
	@DisplayName("another type is an error, and null stays null")
	void other_type_and_null() {
		assertThatThrownBy(() -> converter.fromDatabase("P2Y"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("cannot convert java.lang.String to java.time.Period");
		assertThat(ScalarCodec.decode(null, Period.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
