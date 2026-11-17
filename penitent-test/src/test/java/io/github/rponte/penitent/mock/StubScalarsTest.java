package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/** A stub of a function that returns a BOOLEAN, a TIMESTAMP or a DATE. The real routines fail. */
class StubScalarsTest {

	@PlsqlPackage("FREIGHT_SCALARS")
	interface Scalars {
		Boolean isOpen(String pUf);

		LocalDateTime shippedAt(BigDecimal pId);

		LocalDateTime dueOn(BigDecimal pId);

		String statusOf(String pUf);
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Scalars scalars;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		scalars = PlsqlPackages.create(Scalars.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a stub returns a BOOLEAN, true and false")
	void booleans() {
		Scalars mock = mocks.mock(Scalars.class);
		when(mock.isOpen("SP")).thenReturn(true);
		when(mock.isOpen("CE")).thenReturn(false);

		assertThat(scalars.isOpen("SP")).isTrue();
		assertThat(scalars.isOpen("CE")).isFalse();
	}

	@Test
	@DisplayName("a stub returns a null BOOLEAN")
	void null_boolean() {
		assumeFalse(TestDatabase.nullBooleanOutputMisread(),
				"driver 23 reads a null BOOLEAN output from a database before 23ai as true or false");
		when(mocks.mock(Scalars.class).isOpen("RJ")).thenReturn(null);

		assertThat(scalars.isOpen("RJ")).isNull();
	}

	@Test
	@DisplayName("PL/SQL calling the routine gets the stubbed BOOLEAN, null included")
	void boolean_called_from_plsql() {
		Scalars mock = mocks.mock(Scalars.class);
		when(mock.isOpen("SP")).thenReturn(true);
		when(mock.isOpen("CE")).thenReturn(false);
		when(mock.isOpen("RJ")).thenReturn(null);

		assertThat(scalars.statusOf("SP")).isEqualTo("open");
		assertThat(scalars.statusOf("CE")).isEqualTo("closed");
		assertThat(scalars.statusOf("RJ")).isEqualTo("unknown");
	}

	@Test
	@DisplayName("a stub returns a TIMESTAMP from a LocalDateTime, with its fraction")
	void timestamp() {
		LocalDateTime shipped = LocalDateTime.of(2026, 11, 17, 14, 30, 5, 123_456_000);
		when(mocks.mock(Scalars.class).shippedAt(BigDecimal.ONE)).thenReturn(shipped);

		assertThat(scalars.shippedAt(BigDecimal.ONE)).isEqualTo(shipped);
	}

	@Test
	@DisplayName("a stub returns a DATE from a LocalDateTime, with its time, and a null one")
	void date() {
		Scalars mock = mocks.mock(Scalars.class);
		LocalDateTime due = LocalDateTime.of(2026, 12, 1, 18, 0, 0);
		when(mock.dueOn(BigDecimal.ONE)).thenReturn(due);
		when(mock.dueOn(BigDecimal.TEN)).thenReturn(null);

		assertThat(scalars.dueOn(BigDecimal.ONE)).isEqualTo(due);
		assertThat(scalars.dueOn(BigDecimal.TEN)).isNull();
	}
}
