package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.convert.PlsqlConvert;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import oracle.jdbc.OracleConnection;

/**
 * JDBC reads an {@code INDEX BY PLS_INTEGER} as an array: only the values, in index order. But it
 * writes starting at index 0, so the library only accepts it for reading.
 */
class IndexByTest extends OracleIntegrationTest {

	private static final OffsetDateTime DATA = OffsetDateTime.parse("2018-01-15T10:30:00-03:00");

	record Address(String street, Integer num) {
	}

	record WithNumbers(List<BigDecimal> v) {
	}

	record WithText(List<String> v) {
	}

	record WithRegion(List<ZonedDateTime> v) {
	}

	record Quantity(BigDecimal value) {
	}

	public static class QuantityConverter implements PlsqlConverter<Quantity, BigDecimal> {
		@Override
		public BigDecimal toDatabase(Quantity value) {
			return value.value();
		}

		@Override
		public Quantity fromDatabase(BigDecimal value) {
			return new Quantity(value);
		}
	}

	@PlsqlPackage("FIXTURE_INDEX_BY")
	interface IndexBy {
		List<BigDecimal> sparse();

		void outNumbers(PlsqlOut<List<BigDecimal>> value);

		@PlsqlRoutine("READ_TIME_ZONES")
		List<OffsetDateTime> timeZones();

		@PlsqlRoutine("READ_TIME_ZONES")
		List<Instant> timeZonesAsInstant();

		@PlsqlRoutine("READ_ADDRESSES")
		List<Address> addresses();

		@PlsqlRoutine("A_RECORD")
		WithNumbers aRecord();

		@PlsqlRoutine("SPARSE")
		@PlsqlConvert(QuantityConverter.class)
		List<Quantity> withConverter();

		List<BigDecimal> echoNumbers(List<BigDecimal> value);

		void changeNumbers(PlsqlOut<List<BigDecimal>> value);

		@PlsqlRoutine("ECHO_RECORD")
		WithNumbers echoRecord(WithNumbers value);

		@PlsqlRoutine("ECHO_NUMBERS")
		int nullAlsoRefused(List<BigDecimal> value);

		@PlsqlRoutine("READ_TIME_ZONES")
		List<ZonedDateTime> withRegion();

		@PlsqlRoutine("A_RECORD")
		WithRegion recordWithRegion();

		WithText echoTextRecord(WithText value);

		List<String> textKeys();
	}

	private final IndexBy indexBy = pkg(IndexBy.class);

	@Test
	@DisplayName("on read, it comes in index order and the indexes are lost, sparse ones included")
	void read() {
		assertThat(indexBy.sparse()).extracting(BigDecimal::intValue).containsExactly(10, 50, 90);
	}

	@Test
	@DisplayName("as OUT, of TIMESTAMP WITH TIME ZONE, of OBJECT and as a RECORD field")
	void ways_of_reading() {
		PlsqlOut<List<BigDecimal>> output = new PlsqlOut<>();
		indexBy.outNumbers(output);

		assertThat(output.get()).containsExactly(new BigDecimal("7"));
		assertThat(indexBy.timeZones()).containsExactly(DATA);
		assertThat(indexBy.timeZonesAsInstant()).containsExactly(DATA.toInstant());
		assertThat(indexBy.addresses()).containsExactly(new Address("Street A", 1));
		assertThat(indexBy.aRecord().v()).containsExactly(new BigDecimal("7"));
	}

	@Test
	@DisplayName("per-element converter, on read")
	void with_converter() {
		assertThat(indexBy.withConverter()).extracting(q -> q.value().intValue()).containsExactly(10, 50, 90);
	}

	@Test
	@DisplayName("the reason for the rejection: the driver writes starting at index 0. If that changes, the rejection can go")
	void index_base() throws SQLException {
		String sql = "declare l FIXTURE_INDEX_BY.NUMBERS := ?; begin ? := l.first || '..' || l.last; end;";
		try (Connection connection = DATA_SOURCE.getConnection();
				CallableStatement statement = connection.prepareCall(sql)) {
			statement.setArray(1, connection.unwrap(OracleConnection.class).createOracleArray(
					"FIXTURE_INDEX_BY.NUMBERS", new Object[] { BigDecimal.ONE, BigDecimal.TEN }));
			statement.registerOutParameter(2, Types.VARCHAR);
			statement.execute();

			assertThat(statement.getString(2)).isEqualTo("0..1");
		}
	}

	@Test
	@DisplayName("IN or IN OUT parameter and RECORD field are rejected on write, null included")
	void write_rejected() {
		String message = "starting at index 0";

		assertThatThrownBy(() -> indexBy.echoNumbers(List.of(BigDecimal.ONE)))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(message);
		assertThatThrownBy(() -> indexBy.nullAlsoRefused(null))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(message);
		assertThatThrownBy(() -> indexBy.changeNumbers(PlsqlOut.of(List.of(BigDecimal.ONE))))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(message);
		assertThatThrownBy(() -> indexBy.echoRecord(new WithNumbers(List.of(BigDecimal.ONE))))
				.isInstanceOf(PlsqlMappingException.class).hasMessageContaining(message);
	}

	@Test
	@DisplayName("ZonedDateTime is rejected: the driver would hand back a null element, with no error")
	void region_rejected() {
		assertThatThrownBy(indexBy::withRegion)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare OffsetDateTime or Instant");
		assertThatThrownBy(indexBy::recordWithRegion)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declare OffsetDateTime or Instant");
	}

	@Test
	@DisplayName("INDEX BY VARCHAR2 is rejected: as parameter, return and RECORD field")
	void by_varchar2_rejected() {
		assertThatThrownBy(indexBy::textKeys)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("FIXTURE_INDEX_BY.BY_TEXT")
				.hasMessageContaining("does not carry the keys");
		assertThatThrownBy(() -> indexBy.echoTextRecord(new WithText(List.of("a"))))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("does not carry the keys");
	}
}
