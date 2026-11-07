package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * The driver derives the session {@code NLS_TERRITORY} from the JVM locale, and that territory
 * decides how a number becomes text. The library cannot depend on that. Here each pool pins its
 * own territory, so the result does not depend on the machine running the test.
 */
class LocaleTest extends OracleIntegrationTest {

	private static final HikariDataSource AMERICA = withTerritory("AMERICA");
	private static final HikariDataSource BRAZIL = withTerritory("BRAZIL");

	@PlsqlPackage("FIXTURE_TYPES")
	interface Types {
		BigDecimal echoFloat(BigDecimal value);

		Double echoBinaryDouble(Double value);

		Float echoBinaryFloat(Float value);
	}

	@PlsqlPackage("FIXTURE_CURSOR_LOB")
	interface SpecialChars {
		Double notANumber();
	}

	private final Types inAmerica = PlsqlPackages.create(Types.class, AMERICA);
	private final Types inBrazil = PlsqlPackages.create(Types.class, BRAZIL);

	private static HikariDataSource withTerritory(String territory) {
		HikariDataSource pool = newPool();
		pool.setConnectionInitSql("ALTER SESSION SET NLS_TERRITORY = '" + territory + "'");
		return pool;
	}

	@AfterAll
	static void closePools() {
		AMERICA.close();
		BRAZIL.close();
	}

	@Test
	@DisplayName("FLOAT and BINARY_DOUBLE, with a period as the decimal separator")
	void decimal_point() {
		assertThat(inAmerica.echoFloat(new BigDecimal("1.5"))).isEqualByComparingTo("1.5");
		assertThat(inAmerica.echoBinaryDouble(1.5d)).isEqualTo(1.5d);
	}

	@Test
	@DisplayName("FLOAT with a comma as the decimal separator")
	void float_with_decimal_comma() {
		assertThat(inBrazil.echoFloat(new BigDecimal("1.5"))).isEqualByComparingTo("1.5");
	}

	@Test
	@DisplayName("BINARY_DOUBLE with a comma as the decimal separator")
	void binary_double_with_decimal_comma() {
		assertThat(inBrazil.echoBinaryDouble(1.5d)).isEqualTo(1.5d);
	}

	@Test
	@DisplayName("BINARY_FLOAT becomes Float, with a period as the decimal separator")
	void binary_float_with_decimal_point() {
		assertThat(inAmerica.echoBinaryFloat(1.5f)).isEqualTo(1.5f);
	}

	@Test
	@DisplayName("BINARY_FLOAT becomes Float, with a comma as the decimal separator")
	void binary_float_with_decimal_comma() {
		assertThat(inBrazil.echoBinaryFloat(1.5f)).isEqualTo(1.5f);
	}

	@Test
	@DisplayName("BINARY_DOUBLE_NAN does not go through BigDecimal, which cannot represent it")
	void not_a_number() {
		assertThat(PlsqlPackages.create(SpecialChars.class, BRAZIL).notANumber()).isNaN();
	}
}
