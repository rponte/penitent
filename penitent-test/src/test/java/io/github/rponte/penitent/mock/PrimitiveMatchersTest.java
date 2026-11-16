package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.any;
import static io.github.rponte.penitent.mock.PlsqlMocks.anyBoolean;
import static io.github.rponte.penitent.mock.PlsqlMocks.anyByte;
import static io.github.rponte.penitent.mock.PlsqlMocks.anyDouble;
import static io.github.rponte.penitent.mock.PlsqlMocks.anyFloat;
import static io.github.rponte.penitent.mock.PlsqlMocks.anyInt;
import static io.github.rponte.penitent.mock.PlsqlMocks.anyLong;
import static io.github.rponte.penitent.mock.PlsqlMocks.anyShort;
import static io.github.rponte.penitent.mock.PlsqlMocks.doNothing;
import static io.github.rponte.penitent.mock.PlsqlMocks.doThrow;
import static io.github.rponte.penitent.mock.PlsqlMocks.eq;
import static io.github.rponte.penitent.mock.PlsqlMocks.times;
import static io.github.rponte.penitent.mock.PlsqlMocks.verify;
import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * Matchers for parameters of primitive types: {@code any()} returns {@code null}, which Java unboxes before the call
 * reaches the mock, so each primitive type has its own, matching any value and returning the type's zero.
 */
class PrimitiveMatchersTest {

	/** FREIGHTS.CALCULATE(p_weight NUMBER), through each primitive type. */
	@PlsqlPackage("FREIGHTS")
	interface ByInt {
		BigDecimal calculate(int pWeight);
	}

	@PlsqlPackage("FREIGHTS")
	interface ByLong {
		BigDecimal calculate(long pWeight);
	}

	@PlsqlPackage("FREIGHTS")
	interface ByShort {
		BigDecimal calculate(short pWeight);
	}

	@PlsqlPackage("FREIGHTS")
	interface ByByte {
		BigDecimal calculate(byte pWeight);
	}

	@PlsqlPackage("FREIGHTS")
	interface ByDouble {
		BigDecimal calculate(double pWeight);
	}

	@PlsqlPackage("FREIGHTS")
	interface ByFloat {
		BigDecimal calculate(float pWeight);
	}

	@PlsqlPackage("FREIGHTS")
	interface Flags {
		String describeFlag(boolean pFlag);

		void integrate(int pOperation, String pName);
	}

	private static DataSource dataSource;

	private PlsqlMocks mocks;

	@BeforeAll
	static void startDatabase() {
		dataSource = PlsqlMocks.wrap(TestDatabase.pool());
	}

	@BeforeEach
	void begin() {
		mocks = PlsqlMocks.on(dataSource);
		mocks.begin();
	}

	@AfterEach
	void end() {
		mocks.end();
	}

	private static <T> T real(Class<T> type) {
		return PlsqlPackages.create(type, dataSource);
	}

	@Test
	@DisplayName("anyInt() matches any int")
	void any_int() {
		when(mocks.mock(ByInt.class).calculate(anyInt())).thenReturn(new BigDecimal("7"));

		assertThat(real(ByInt.class).calculate(3)).isEqualByComparingTo("7");
		assertThat(real(ByInt.class).calculate(-40)).isEqualByComparingTo("7");
	}

	@Test
	@DisplayName("anyLong(), anyShort() and anyByte() match any value of their type")
	void any_long_short_byte() {
		when(mocks.mock(ByLong.class).calculate(anyLong())).thenReturn(new BigDecimal("7"));
		assertThat(real(ByLong.class).calculate(3_000_000_000L)).isEqualByComparingTo("7");

		mocks.end();
		mocks.begin();
		when(mocks.mock(ByShort.class).calculate(anyShort())).thenReturn(new BigDecimal("8"));
		assertThat(real(ByShort.class).calculate((short) 12)).isEqualByComparingTo("8");

		mocks.end();
		mocks.begin();
		when(mocks.mock(ByByte.class).calculate(anyByte())).thenReturn(new BigDecimal("9"));
		assertThat(real(ByByte.class).calculate((byte) 1)).isEqualByComparingTo("9");
	}

	@Test
	@DisplayName("anyDouble() and anyFloat() match any value of their type")
	void any_double_float() {
		when(mocks.mock(ByDouble.class).calculate(anyDouble())).thenReturn(new BigDecimal("7"));
		assertThat(real(ByDouble.class).calculate(1.5)).isEqualByComparingTo("7");

		mocks.end();
		mocks.begin();
		when(mocks.mock(ByFloat.class).calculate(anyFloat())).thenReturn(new BigDecimal("8"));
		assertThat(real(ByFloat.class).calculate(2.5f)).isEqualByComparingTo("8");
	}

	@Test
	@DisplayName("anyBoolean() matches true and false")
	void any_boolean() {
		when(mocks.mock(Flags.class).describeFlag(anyBoolean())).thenReturn("stubbed");

		assertThat(real(Flags.class).describeFlag(true)).isEqualTo("stubbed");
		assertThat(real(Flags.class).describeFlag(false)).isEqualTo("stubbed");
	}

	@Test
	@DisplayName("a procedure taking an int, stubbed for any operation, with the other argument matched by value")
	void procedure() {
		doThrow(-20300, "not integrated").when(mocks.mock(Flags.class)).integrate(anyInt(), eq("VERBA"));

		assertThatThrownBy(() -> real(Flags.class).integrate(781, "VERBA"))
				.isInstanceOf(PlsqlRoutineRaisedException.class)
				.hasMessageContaining("not integrated");
		assertThatThrownBy(() -> real(Flags.class).integrate(201, "OTHER"))
				.as("no stub matches another name").hasMessageContaining("ORA-20999");
	}

	@Test
	@DisplayName("verify counts the calls with any int, as a stub matches them")
	void verify_with_any_int() {
		Flags mock = mocks.mock(Flags.class);
		doNothing().when(mock).integrate(anyInt(), any());

		real(Flags.class).integrate(781, "VERBA");
		real(Flags.class).integrate(201, "VERBA");

		verify(mock, times(2)).integrate(anyInt(), eq("VERBA"));
		verify(mock, times(1)).integrate(eq(781), any());
	}
}
