package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * How an {@code @PlsqlSchema} interface's routines resolve their names, each on its own, as Oracle does (#169): an
 * object of the schema first, then a private synonym and, with no schema declared, a public one; a synonym may point
 * to another synonym, up to 10, and a cycle or a synonym to nothing resolves to nothing. Each function returns a
 * different value, so that a test shows which one ran. The fixtures are in {@code V30__legacy_standalone.sql} and
 * {@code owner/V2__legacy_standalone.sql}.
 */
class StandaloneResolutionTest extends OracleIntegrationTest {

	/** A legacy schema's routines, as {@code V30__legacy_standalone.sql} creates them. */
	@PlsqlSchema
	interface Legacy {
		@PlsqlRoutine("LEG_OWN_01")
		BigDecimal legOwn01(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_02")
		BigDecimal legOwn02(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_03")
		BigDecimal legOwn03(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_04")
		BigDecimal legOwn04(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_05")
		BigDecimal legOwn05(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_06")
		BigDecimal legOwn06(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_07")
		BigDecimal legOwn07(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_08")
		BigDecimal legOwn08(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_09")
		BigDecimal legOwn09(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_10")
		BigDecimal legOwn10(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_11")
		BigDecimal legOwn11(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_12")
		BigDecimal legOwn12(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_13")
		BigDecimal legOwn13(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_14")
		BigDecimal legOwn14(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_15")
		BigDecimal legOwn15(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_16")
		BigDecimal legOwn16(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_17")
		BigDecimal legOwn17(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_18")
		BigDecimal legOwn18(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_19")
		BigDecimal legOwn19(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_OWN_20")
		BigDecimal legOwn20(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_PRIV_01")
		BigDecimal legPriv01(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_PRIV_02")
		BigDecimal legPriv02(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_PRIV_03")
		BigDecimal legPriv03(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_PRIV_04")
		BigDecimal legPriv04(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_PRIV_05")
		BigDecimal legPriv05(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_PUB_01")
		BigDecimal legPub01(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_PUB_02")
		BigDecimal legPub02(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_PUB_03")
		BigDecimal legPub03(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_CHAIN_01")
		BigDecimal legChain01(BigDecimal pA, String pB);

		@PlsqlRoutine("LEG_CHAIN_02")
		BigDecimal legChain02(BigDecimal pA, String pB);
	}

	@PlsqlSchema
	interface Precedence {
		/** A function of the schema, and a public synonym of the same name: the function. */
		BigDecimal precShadowed(BigDecimal pA);

		/** A private synonym, and a public one of the same name: the private one. */
		BigDecimal precBoth(BigDecimal pA);

		/** Only a public synonym. */
		BigDecimal precPublicOnly(BigDecimal pA);

		BigDecimal precCycleA(BigDecimal pA);

		BigDecimal precDangling(BigDecimal pA);

		BigDecimal precNoSuchName(BigDecimal pA);
	}

	@PlsqlSchema("plsql_test")
	interface PrecedenceInSchema {
		/** With a schema declared, a public synonym does not count. */
		BigDecimal precPublicOnly(BigDecimal pA);

		BigDecimal precBoth(BigDecimal pA);
	}

	@Test
	@DisplayName("a function of the schema comes before a public synonym of the same name")
	void object_before_public_synonym() {
		assertThat(PlsqlPackages.create(Precedence.class, DATA_SOURCE).precShadowed(BigDecimal.TEN))
				.isEqualByComparingTo("1");
	}

	@Test
	@DisplayName("a private synonym comes before a public one of the same name")
	void private_before_public_synonym() {
		assertThat(PlsqlPackages.create(Precedence.class, DATA_SOURCE).precBoth(BigDecimal.TEN))
				.isEqualByComparingTo("1");
	}

	@Test
	@DisplayName("a public synonym, when nothing else has the name")
	void public_synonym() {
		assertThat(PlsqlPackages.create(Precedence.class, DATA_SOURCE).precPublicOnly(BigDecimal.TEN))
				.isEqualByComparingTo("3");
	}

	@Test
	@DisplayName("with a schema declared, a public synonym does not count, and a private one does")
	void declared_schema_ignores_public_synonyms() {
		PrecedenceInSchema inSchema = PlsqlPackages.create(PrecedenceInSchema.class, DATA_SOURCE);

		assertThatThrownBy(() -> inSchema.precPublicOnly(BigDecimal.TEN)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PREC_PUBLIC_ONLY does not exist");
		assertThat(inSchema.precBoth(BigDecimal.TEN)).isEqualByComparingTo("1");
	}

	@Test
	@DisplayName("a cycle of synonyms, a synonym to nothing and a name that does not exist fail as not existing")
	void cycle_dangling_and_missing() {
		Precedence precedence = PlsqlPackages.create(Precedence.class, DATA_SOURCE);

		assertThatThrownBy(() -> precedence.precCycleA(BigDecimal.TEN)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PREC_CYCLE_A does not exist");
		assertThatThrownBy(() -> precedence.precDangling(BigDecimal.TEN)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PREC_DANGLING does not exist");
		assertThatThrownBy(() -> precedence.precNoSuchName(BigDecimal.TEN)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PREC_NO_SUCH_NAME does not exist");
		assertThat(precedence.precShadowed(BigDecimal.TEN)).isEqualByComparingTo("1");
	}

	@Test
	@DisplayName("validating the interface finds the same as calling each method")
	void validate_agrees_with_the_calls() {
		Precedence precedence = PlsqlPackages.create(Precedence.class, DATA_SOURCE);

		assertThatThrownBy(() -> PlsqlPackages.validate(precedence)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("3 methods of").hasMessageContaining("PREC_CYCLE_A does not exist")
				.hasMessageContaining("PREC_DANGLING does not exist")
				.hasMessageContaining("PREC_NO_SUCH_NAME does not exist");
		assertThat(precedence.precShadowed(BigDecimal.TEN)).isEqualByComparingTo("1");
		assertThat(precedence.precBoth(BigDecimal.TEN)).isEqualByComparingTo("1");
		assertThat(precedence.precPublicOnly(BigDecimal.TEN)).isEqualByComparingTo("3");
	}

	@Test
	@DisplayName("every routine of the legacy schema, of its own, through private, public and chained synonyms")
	void legacy() {
		Legacy legacy = PlsqlPackages.create(Legacy.class, DATA_SOURCE);
		PlsqlPackages.validate(legacy);

		assertThat(legacy.legOwn07(BigDecimal.ONE, "x")).isEqualByComparingTo("1");
		assertThat(legacy.legPriv03(BigDecimal.TEN, "x")).isEqualByComparingTo("10");
		assertThat(legacy.legPub02(BigDecimal.valueOf(2), "x")).isEqualByComparingTo("2");
		assertThat(legacy.legChain02(BigDecimal.ONE, "x")).isEqualByComparingTo("1");
	}

	@Test
	@DisplayName("after the first call of one method, another reads nothing, but the signatures of another owner, once")
	void read_once_for_the_interface() {
		QueryCounter counter = new QueryCounter(DATA_SOURCE);
		Legacy legacy = PlsqlPackages.create(Legacy.class, counter.dataSource());
		legacy.legOwn01(BigDecimal.ONE, "x");
		counter.reset();

		legacy.legOwn02(BigDecimal.ONE, "x");
		legacy.legChain01(BigDecimal.ONE, "x");
		assertThat(counter.dictionaryQueries()).as("of the same owner, resolved and read").isZero();

		legacy.legPriv01(BigDecimal.ONE, "x");
		legacy.legPub01(BigDecimal.ONE, "x");
		legacy.legPriv02(BigDecimal.ONE, "x");
		assertThat(counter.dictionaryQueries()).as("the other owner's signatures, read once").isEqualTo(1);
	}
}
