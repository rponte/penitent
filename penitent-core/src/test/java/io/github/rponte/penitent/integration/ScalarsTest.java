package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

class ScalarsTest extends OracleIntegrationTest {

	enum Color {
		BLUE, GREEN
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Scalars {
		int constant();

		BigDecimal plus(BigDecimal a, BigDecimal b);

		@PlsqlRoutine("ECHO_NUMBER")
		Integer echoInteger(Integer value);

		@PlsqlRoutine("ECHO_NUMBER")
		Long echoLong(Long value);

		@PlsqlRoutine("ECHO_NUMBER")
		Double echoDouble(Double value);

		@PlsqlRoutine("ECHO_NUMBER")
		int echoPrimitive(Integer value);

		String echoText(String value);

		@PlsqlRoutine("ECHO_TEXT")
		Color echoEnum(Color value);

		@PlsqlRoutine("ECHO_TEXT")
		Boolean echoBooleanAsText(Boolean value);

		@PlsqlRoutine("ECHO_TEXT")
		String booleanAsText(Boolean value);

		@PlsqlRoutine("ECHO_TEXT")
		Boolean textAsBoolean(String value);

		@PlsqlRoutine("ECHO_NUMBER")
		int booleanAsNumber(boolean value);

		@PlsqlRoutine("ECHO_NUMBER")
		Integer booleanWrapperAsNumber(Boolean value);

		LocalDate echoDate(LocalDate value);

		LocalDateTime echoTimestamp(LocalDateTime value);

		boolean isPositive(int value);

		int withDefault(int a);

		@PlsqlRoutine("WITH_DEFAULT")
		int withDefaultSupplied(int a, int b);

		/** Java overload with the same name as the method above, which omits p_b. */
		int withDefault(int a, int b);

		@PlsqlRoutine("WITH_DEFAULT")
		Integer withDefaultNull(int a, Integer b);

		void register(int id, String description);

		void noArguments();

		boolean negate(boolean value);

		@PlsqlRoutine("NEGATE")
		Boolean negateWrapper(Boolean value);
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface Overload {
		@PlsqlRoutine("OVERLOADED")
		int oneParameter(int a);

		@PlsqlRoutine("OVERLOADED")
		String twoParameters(String a, String b);
	}

	/** The explicit schema, instead of the session's. */
	@PlsqlPackage(value = "FIXTURE_SCALARS", schema = "plsql_test")
	interface ScalarsWithSchema {
		int constant();
	}

	private final Scalars scalars = pkg(Scalars.class);

	@Test
	@DisplayName("function without arguments, with the camelCase -> SNAKE_CASE naming convention")
	void function_without_arguments() {
		assertThat(scalars.constant()).isEqualTo(42);
	}

	@Test
	@DisplayName("parameters bind by position")
	void parameters_by_position() {
		assertThat(scalars.plus(new BigDecimal("1.5"), new BigDecimal("2.25"))).isEqualByComparingTo("3.75");
	}

	@Test
	@DisplayName("NUMBER comes back as the numeric type Java declared")
	void numbers() {
		assertThat(scalars.echoInteger(7)).isEqualTo(7);
		assertThat(scalars.echoLong(5_000_000_000L)).isEqualTo(5_000_000_000L);
		assertThat(scalars.echoDouble(1.5d)).isEqualTo(1.5d);
	}

	@Test
	@DisplayName("text, enum by name() and boolean as Y/N, in both directions")
	void text_enum_and_boolean() {
		assertThat(scalars.echoText("café")).isEqualTo("café");
		assertThat(scalars.echoEnum(Color.GREEN)).isEqualTo(Color.GREEN);
		assertThat(scalars.echoBooleanAsText(true)).isTrue();
		assertThat(scalars.echoBooleanAsText(false)).isFalse();
	}

	@Test
	@DisplayName("the default boolean literals in text are Y and N")
	void default_literals() {
		assertThat(scalars.booleanAsText(true)).isEqualTo("Y");
		assertThat(scalars.booleanAsText(false)).isEqualTo("N");
		assertThat(scalars.textAsBoolean("Y")).isTrue();
		assertThat(scalars.textAsBoolean("N")).isFalse();
	}

	@Test
	@DisplayName("S is not a default literal: reading it is an error, instead of false")
	void s_is_not_a_default_literal() {
		assertThatThrownBy(() -> scalars.textAsBoolean("S"))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("'S'")
				.hasMessageContaining("'Y'");
	}

	@Test
	@DisplayName("boolean in a NUMBER parameter goes as 1 and 0")
	void boolean_in_number_parameter() {
		assertThat(scalars.booleanAsNumber(true)).isEqualTo(1);
		assertThat(scalars.booleanAsNumber(false)).isZero();
		assertThat(scalars.booleanWrapperAsNumber(null)).isNull();
	}

	@Test
	@DisplayName("DATE becomes LocalDate and TIMESTAMP becomes LocalDateTime")
	void dates() {
		assertThat(scalars.echoDate(LocalDate.parse("2018-01-15"))).isEqualTo("2018-01-15");
		assertThat(scalars.echoTimestamp(LocalDateTime.parse("2018-01-15T10:30:45")))
				.isEqualTo("2018-01-15T10:30:45");
	}

	@Test
	@DisplayName("null passes through as null, and a primitive refuses it, so as not to hide the NULL")
	void nulls() {
		assertThat(scalars.echoInteger(null)).isNull();
		assertThat(scalars.echoText(null)).isNull();
		assertThat(scalars.echoEnum(null)).isNull();
		assertThat(scalars.echoDate(null)).isNull();
		assertThatThrownBy(() -> scalars.echoPrimitive(null))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NULL does not fit in int")
				.hasMessageContaining("declare Integer");
	}

	@Test
	@DisplayName("PL/SQL BOOLEAN comes back as boolean")
	void boolean_in_return() {
		assertThat(scalars.isPositive(1)).isTrue();
		assertThat(scalars.isPositive(-1)).isFalse();
	}

	@Test
	@DisplayName("a parameter with DEFAULT can be omitted, and Oracle fills it in")
	void parameter_with_default() {
		assertThat(scalars.withDefault(1)).isEqualTo(11);
		assertThat(scalars.withDefaultSupplied(1, 2)).isEqualTo(3);
	}

	@Test
	@DisplayName("DEFAULT via Java overload with the same name: each method sends only its own parameters")
	void default_via_java_overload() {
		assertThat(scalars.withDefault(1)).isEqualTo(11);
		assertThat(scalars.withDefault(1, 2)).isEqualTo(3);
	}

	@Test
	@DisplayName("null in a parameter with DEFAULT goes as NULL: Oracle does not replace it with the default")
	void null_does_not_trigger_the_default() {
		assertThat(scalars.withDefaultNull(1, null)).isNull();
	}

	@Test
	@DisplayName("procedure really executes")
	void procedure() throws SQLException {
		scalars.noArguments();
		scalars.register(9001, "via procedure");

		try (Connection connection = DATA_SOURCE.getConnection();
				PreparedStatement select = connection.prepareStatement(
						"select description from fixture_item where id = 9001");
				ResultSet rs = select.executeQuery()) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getString(1)).isEqualTo("via procedure");
		}
	}

	@Test
	@DisplayName("explicit schema in @PlsqlPackage, in any case")
	void explicit_schema() {
		assertThat(pkg(ScalarsWithSchema.class).constant()).isEqualTo(42);
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface WithError {
		void raiseError(String message);
	}

	@Test
	@DisplayName("RAISE_APPLICATION_ERROR arrives with the code and the message in the cause")
	void business_error() {
		assertThatThrownBy(() -> pkg(WithError.class).raiseError("rule violated"))
				.cause()
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("ORA-20100: rule violated");
	}

	@Test
	@DisplayName("PL/SQL BOOLEAN as a parameter")
	void boolean_in_parameter() {
		assertThat(scalars.negate(true)).isFalse();
		assertThat(scalars.negate(false)).isTrue();
	}

	@Test
	@DisplayName("PL/SQL BOOLEAN as a parameter, with the wrapper and with null")
	void boolean_wrapper_in_parameter() {
		assertThat(scalars.negateWrapper(true)).isFalse();
		assertThat(scalars.negateWrapper(Boolean.FALSE)).isTrue();
		assertThat(scalars.negateWrapper(null)).isNull();
	}

	@Test
	@DisplayName("overloaded routine: each method calls its own version")
	void overloading() {

		Overload overload = pkg(Overload.class);

		assertThat(overload.oneParameter(7)).isEqualTo(7);
		assertThat(overload.twoParameters("a", "b")).isEqualTo("ab");
	}
}
