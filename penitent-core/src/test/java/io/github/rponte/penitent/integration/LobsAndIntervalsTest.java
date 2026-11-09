package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Blob;
import java.sql.Clob;
import java.time.Period;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;

/** LOBs above the 32 KB of a VARCHAR2 bind, in both directions, and INTERVAL YEAR TO MONTH. */
class LobsAndIntervalsTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_LOBS")
	interface Lobs {
		int clobLength(String value);

		@PlsqlRoutine("CLOB_LENGTH")
		int clobLengthOf(Clob value);

		int blobLength(byte[] value);

		@PlsqlRoutine("BLOB_LENGTH")
		int blobLengthOf(Blob value);

		void largeClob(int size, PlsqlOut<String> value);

		Period echoIntervalYm(Period value);
	}

	@PlsqlPackage("FIXTURE_TYPES")
	interface Types {
		String echoClob(String value);

		byte[] echoBlob(byte[] value);

		@PlsqlRoutine("ECHO_BLOB")
		Blob echoBlobNative(Blob value);
	}

	private final Lobs lobs = pkg(Lobs.class);
	private final Types types = pkg(Types.class);

	@Test
	@DisplayName("a String above 32 KB reaches a CLOB parameter whole")
	void large_clob_parameter() {
		assertThat(lobs.clobLength("x".repeat(100_000))).isEqualTo(100_000);
	}

	@Test
	@DisplayName("a CLOB above 32 KB in an OUT parameter becomes a String")
	void large_clob_out() {
		PlsqlOut<String> value = new PlsqlOut<>();

		lobs.largeClob(100_000, value);

		assertThat(value.get()).hasSize(100_000).matches("y+");
	}

	@Test
	@DisplayName("a java.sql.Clob that did not come from the driver goes in a temporary CLOB")
	void clob_parameter() throws Exception {
		assertThat(lobs.clobLengthOf(new SerialClob("abc".toCharArray()))).isEqualTo(3);
	}

	@Test
	@DisplayName("a byte[] above 32 KB reaches a BLOB parameter whole")
	void large_blob_parameter() {
		assertThat(lobs.blobLength(new byte[100_000])).isEqualTo(100_000);
	}

	@Test
	@DisplayName("java.sql.Blob in both directions, as a copy that does not depend on the connection")
	void blob_native() throws Exception {
		Blob echoed = types.echoBlobNative(new SerialBlob(new byte[] { 1, 2, 3 }));

		assertThat(echoed.getBytes(1, (int) echoed.length())).containsExactly(1, 2, 3);
		assertThat(lobs.blobLengthOf(new SerialBlob(new byte[] { 1 }))).isEqualTo(1);
	}

	@Test
	@DisplayName("NULL in a CLOB and in a BLOB stays null")
	void null_lobs() {
		assertThat(types.echoClob(null)).isNull();
		assertThat(types.echoBlob(null)).isNull();
	}

	@Test
	@DisplayName("INTERVAL YEAR TO MONTH becomes Period")
	void interval_year_to_month() {
		assertThat(lobs.echoIntervalYm(Period.of(2, 3, 0))).isEqualTo(Period.of(2, 3, 0));
	}

	@Test
	@DisplayName("a Period with days is refused before the call: INTERVAL YEAR TO MONTH does not store them")
	void period_with_days() {
		assertThatThrownBy(() -> lobs.echoIntervalYm(Period.of(1, 2, 3)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessage("echoIntervalYm(Period): Period P1Y2M3D has days, which INTERVAL YEAR TO MONTH does not store");
	}

	@Test
	@DisplayName("many calls with temporary LOBs in the same pooled session do not fail")
	void temporary_lobs_are_freed() {
		for (int i = 0; i < 50; i++) {
			assertThat(lobs.clobLength("x".repeat(40_000))).isEqualTo(40_000);
		}
	}
}
