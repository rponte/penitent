package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Clob;

import javax.sql.rowset.serial.SerialClob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class ClobConverterTest {

	private final ClobConverter converter = new ClobConverter();

	@Test
	@DisplayName("reads a copy in memory, from a Clob or a text")
	void read() throws Exception {
		Clob source = new SerialClob("abc".toCharArray());

		Clob copy = converter.fromDatabase(source);

		assertThat(copy).isNotSameAs(source);
		assertThat(copy.getSubString(1, 3)).isEqualTo("abc");
		assertThat(converter.fromDatabase("xyz").getSubString(1, 3)).isEqualTo("xyz");
	}

	@Test
	@DisplayName("writes as is: the binding layer moves it to a temporary CLOB")
	void write() throws Exception {
		Clob value = new SerialClob("a".toCharArray());

		assertThat(converter.toDatabase(value)).isSameAs(value);
	}

	@Test
	@DisplayName("another type is an error, and null stays null")
	void other_type_and_null() {
		assertThatThrownBy(() -> converter.fromDatabase(1))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("cannot convert java.lang.Integer to java.sql.Clob");
		assertThat(ScalarCodec.decode(null, Clob.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
