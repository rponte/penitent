package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * Stubs of routines whose composite types have quoted names holding a dot, such as {@code "Dot.Crate_T"}, which a
 * name split on every dot would cut in two. The real routines fail, so a test passes only through the stub.
 */
class StubDottedNamesTest {

	record Crate(BigDecimal id, String name) {
	}

	@PlsqlPackage("FREIGHT_DOTS")
	interface Dots {
		Crate rowOf(BigDecimal pId);

		Crate objectOf(BigDecimal pId);

		List<Crate> objectsOf(BigDecimal pId);

		Crate recordOf(BigDecimal pId);

		List<Crate> rowsOf(BigDecimal pId);

		String nameOf(Crate pCrate);

		Crate twinOf(BigDecimal pId);
	}

	@PlsqlPackage("\"Freight.Dots\"")
	interface DottedPackage {
		Crate itemOf(BigDecimal pId);
	}

	private static final Crate CRATE = new Crate(BigDecimal.ONE, "one");
	private static final List<Crate> CRATES = List.of(CRATE, new Crate(BigDecimal.TEN, "ten"));

	private static DataSource dataSource;

	private PlsqlMocks mocks;
	private Dots dots;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
		dots = PlsqlPackages.create(Dots.class, dataSource);
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	@Test
	@DisplayName("a %ROWTYPE of a table whose name holds a dot")
	void rowtype() {
		when(mocks.mock(Dots.class).rowOf(BigDecimal.ONE)).thenReturn(CRATE);

		assertThat(dots.rowOf(BigDecimal.ONE)).isEqualTo(CRATE);
	}

	@Test
	@DisplayName("an object type and a collection of it whose names hold a dot")
	void object_and_collection() {
		Dots mock = mocks.mock(Dots.class);
		when(mock.objectOf(BigDecimal.ONE)).thenReturn(CRATE);
		when(mock.objectsOf(BigDecimal.ONE)).thenReturn(CRATES);

		assertThat(dots.objectOf(BigDecimal.ONE)).isEqualTo(CRATE);
		assertThat(dots.objectsOf(BigDecimal.ONE)).isEqualTo(CRATES);
	}

	@Test
	@DisplayName("a package RECORD whose name holds a dot")
	void record() {
		when(mocks.mock(Dots.class).recordOf(BigDecimal.ONE)).thenReturn(CRATE);

		assertThat(dots.recordOf(BigDecimal.ONE)).isEqualTo(CRATE);
	}

	@Test
	@DisplayName("a collection of rows of a table whose name holds a dot")
	void rows() {
		when(mocks.mock(Dots.class).rowsOf(BigDecimal.ONE)).thenReturn(CRATES);

		assertThat(dots.rowsOf(BigDecimal.ONE)).isEqualTo(CRATES);
	}

	@Test
	@DisplayName("an object type whose name holds a dot, as an argument any() matches and verify counts")
	void object_argument() {
		Dots mock = mocks.mock(Dots.class);
		when(mock.nameOf(any())).thenReturn("stubbed");

		assertThat(dots.nameOf(CRATE)).isEqualTo("stubbed");
		verify(mock).nameOf(any());
	}

	@Test
	@DisplayName("an object type the driver names as it names a type of a package, refused naming both")
	void twin_names() {
		assertThatThrownBy(() -> when(mocks.mock(Dots.class).twinOf(BigDecimal.ONE)).thenReturn(CRATE))
				.hasMessageContaining("the driver names both \"PLSQL_TEST\".\"Twin.Box_T\" and"
						+ " \"PLSQL_TEST\".\"Twin\".\"Box_T\" PLSQL_TEST.Twin.Box_T")
				.hasMessageContaining("rename one of them");
	}

	@Test
	@DisplayName("a RECORD of a package whose name holds a dot")
	void dotted_package() {
		when(mocks.mock(DottedPackage.class).itemOf(BigDecimal.ONE)).thenReturn(CRATE);

		assertThat(PlsqlPackages.create(DottedPackage.class, dataSource).itemOf(BigDecimal.ONE)).isEqualTo(CRATE);
	}
}
