package io.github.rponte.penitent.internal.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Struct;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.convert.ScalarCodec.ValueConversion;

class StringConverterTest {

	private final StringConverter converter = new StringConverter();

	@SuppressWarnings("unchecked")
	private static <T> T unused(Class<T> type) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			throw new SQLException("connection closed");
		});
	}

	@Test
	@DisplayName("writes as is")
	void writes() {
		assertThat(converter.toDatabase("abc")).isEqualTo("abc");
	}

	@Test
	@DisplayName("reads text without touching the spaces, and any other scalar value via toString")
	void reads() {
		assertThat(converter.fromDatabase(" abc ")).isEqualTo(" abc ");
		assertThat(converter.fromDatabase(new BigDecimal("7.50"))).isEqualTo("7.50");
	}

	@Test
	@DisplayName("reads the content of a CLOB")
	void clob() throws Exception {
		assertThat(converter.fromDatabase(new SerialClob("content".toCharArray()))).isEqualTo("content");
	}

	@Test
	@DisplayName("a failure reading a CLOB is an error with the cause in the message")
	void clob_failure() {
		assertThatThrownBy(() -> converter.fromDatabase(unused(Clob.class)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("failed to read a CLOB")
				.hasMessageContaining("connection closed");
	}

	@Test
	@DisplayName("binary and composite values are errors: their toString does not carry the content")
	void no_content_in_to_string() throws Exception {
		for (Object value : new Object[] { new SerialBlob(new byte[] { 1 }), new byte[] { 1 }, unused(Struct.class),
				unused(Array.class), unused(ResultSet.class) }) {
			assertThatThrownBy(() -> converter.fromDatabase(value))
					.isInstanceOf(PlsqlMappingException.class)
					.hasMessageContaining("to String");
		}
	}

	@Test
	@DisplayName("through the codec: null stays null")
	void null_value() {
		assertThat(ScalarCodec.decode(null, String.class, ValueConversion.DEFAULT, null)).isNull();
	}
}
