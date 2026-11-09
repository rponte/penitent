package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Clob;
import java.sql.SQLException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;

/** Oracle types beyond the basic ones in {@link ScalarsTest}, as parameter and return. */
class OracleTypesTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_TYPES")
	interface Types {
		String echoChar(String value);

		String echoNvarchar2(String value);

		String echoVarchar2(String value);

		Integer echoBinaryInteger(Integer value);

		Integer echoPlsInteger(Integer value);

		String aRowid();

		String echoClob(String value);

		@PlsqlRoutine("ECHO_CLOB")
		Clob echoClobNative(String value);

		byte[] echoBlob(byte[] value);

		byte[] echoRaw(byte[] value);

		Duration echoInterval(Duration value);
	}

	/** What the database received, in its own words. */
	interface InTheDatabase {
		@PlsqlBlock("begin :result := to_char(:value); end;")
		String intervalAsText(Duration value);

		@PlsqlBlock("begin :result := extract(second from :value); end;")
		java.math.BigDecimal secondsOf(Duration value);
	}

	private final Types types = pkg(Types.class);

	@Test
	@DisplayName("CHAR and NVARCHAR2 become String")
	void char_and_nvarchar2() {
		assertThat(types.echoChar("abc")).isEqualTo("abc");
		assertThat(types.echoNvarchar2("café")).isEqualTo("café");
	}

	@Test
	@DisplayName("VARCHAR2 at the PL/SQL limit of 32767 bytes")
	void varchar2_at_the_limit() {
		String text = "x".repeat(32767);

		assertThat(types.echoVarchar2(text)).isEqualTo(text);
	}

	@Test
	@DisplayName("BINARY_INTEGER and PLS_INTEGER become Integer")
	void plsql_integers() {
		assertThat(types.echoBinaryInteger(7)).isEqualTo(7);
		assertThat(types.echoPlsInteger(7)).isEqualTo(7);
	}

	@Test
	@DisplayName("ROWID becomes String")
	void rowid() {
		assertThat(types.aRowid()).isNotBlank();
	}

	@Test
	@DisplayName("short CLOB becomes String")
	void short_clob() {
		assertThat(types.echoClob("abc")).isEqualTo("abc");
	}

	@Test
	@DisplayName("large CLOB becomes String")
	void large_clob() {
		String text = "x".repeat(100_000);

		assertThat(types.echoClob(text)).isEqualTo(text);
	}

	@Test
	@DisplayName("CLOB becomes java.sql.Clob")
	void native_clob() throws SQLException {
		assertThat(types.echoClobNative("abc").getSubString(1, 3)).isEqualTo("abc");
	}

	@Test
	@DisplayName("BLOB becomes byte[]")
	void blob() {
		assertThat(types.echoBlob(new byte[] { 1, 2 })).containsExactly(1, 2);
	}

	@Test
	@DisplayName("RAW becomes byte[]")
	void raw() {
		assertThat(types.echoRaw(new byte[] { 1, 2 })).containsExactly(1, 2);
	}

	@Test
	@DisplayName("INTERVAL DAY TO SECOND becomes Duration")
	void interval() {
		assertThat(types.echoInterval(Duration.ofHours(2))).isEqualTo(Duration.ofHours(2));
	}

	@Test
	@DisplayName("a negative Duration of whole seconds reaches the database as itself, not as a malformed interval")
	void negative_interval() {
		// Driver 23's INTERVALDS.toIntervalds sends these with -1,000,000,000 nanoseconds and one second less,
		// which to_char and extract read as written: -1 second became -0 00:00:00, and a minute -59 seconds.
		InTheDatabase database = PlsqlPackages.create(InTheDatabase.class, DATA_SOURCE);

		assertThat(database.intervalAsText(Duration.ofSeconds(-1))).isEqualTo("-000000000 00:00:01.000000000");
		assertThat(database.intervalAsText(Duration.ofHours(-25).plusMinutes(3)))
				.isEqualTo("-000000001 00:57:00.000000000");
		assertThat(database.secondsOf(Duration.ofMinutes(-1))).isZero();
		assertThat(database.intervalAsText(Duration.ofMillis(-500))).isEqualTo("-000000000 00:00:00.500000000");
	}
}
