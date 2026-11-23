package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * A constraint the routine declares on a formal parameter or its return value is the routine's contract, so a value
 * that breaks it is a {@link PlsqlRoutineRaisedException}, as if the routine checked it on its first line (#141).
 * Oracle checks it when it assigns the value, before the routine's first line, the stack then showing only
 * {@code at line 1}, the same whether the call comes through JDBC or from PL/SQL. Following Oracle's "Formal
 * Parameters of Constrained Subtypes", a parameter inherits {@code NOT NULL} and a range, but not a size nor a
 * precision: a {@code VARCHAR2(3)} subtype, or a column's {@code %TYPE}, takes any size, and a value too long fails
 * later, in the routine's body.
 */
class ParameterConstraintsTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_LIMITS")
	interface Limits {
		String positiveP(BigDecimal pValue);

		String naturalP(BigDecimal pValue);

		String positivenP(BigDecimal pValue);

		String simpleP(BigDecimal pValue);

		String plsP(Long pValue);

		String rangeP(BigDecimal pValue);

		String codeP(String pValue);

		String smallP(BigDecimal pValue);

		String coltypeP(String pValue);

		void storeCode(String pValue);

		BigDecimal retPositive();

		String retCode();
	}

	interface FromPlsql {
		@PlsqlBlock("declare l_value NUMBER := :value; begin :result := fixture_limits.positive_p(l_value); end;")
		String positive(BigDecimal value);
	}

	private Limits limits() {
		return pkg(Limits.class);
	}

	@Test
	@DisplayName("a NOT NULL or a range broken at entry is the routine's error: POSITIVE, SIMPLE_INTEGER, PLS_INTEGER"
			+ " and a subtype's RANGE")
	void entry_constraints_are_the_routines() {
		assertThatThrownBy(() -> limits().positiveP(BigDecimal.valueOf(-1)))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(6502))
				.hasMessageContaining("ORA-06512: at line 1");
		assertThatThrownBy(() -> limits().naturalP(BigDecimal.valueOf(-1)))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(6502));
		assertThatThrownBy(() -> limits().positivenP(null))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(6502));
		assertThatThrownBy(() -> limits().simpleP(null))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(6502));
		assertThatThrownBy(() -> limits().plsP(3_000_000_000L))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(1426));
		assertThatThrownBy(() -> limits().rangeP(BigDecimal.valueOf(11)))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(6502));
		assertThat(limits().rangeP(BigDecimal.TEN)).isEqualTo("10");
	}

	@Test
	@DisplayName("the same from PL/SQL: a block passing a variable checks the constraint at entry, as a call from Java"
			+ " does")
	void the_same_from_plsql() {
		assertThatThrownBy(() -> pkg(FromPlsql.class).positive(BigDecimal.valueOf(-1)))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(6502));
		assertThat(pkg(FromPlsql.class).positive(BigDecimal.ONE)).isEqualTo("1");
	}

	@Test
	@DisplayName("a size or a precision is not inherited: a VARCHAR2(3) subtype, a NUMBER(3) subtype and a column's"
			+ " %TYPE take any value")
	void size_and_precision_are_not_inherited() {
		assertThat(limits().codeP("abcdef")).isEqualTo("abcdef");
		assertThat(limits().smallP(BigDecimal.valueOf(12345))).isEqualTo("12345");
		assertThat(limits().coltypeP("abcdef")).isEqualTo("abcdef");
	}

	@Test
	@DisplayName("a value too long for a column fails later, in the routine's body, as the routine's error")
	void too_long_fails_in_the_body() {
		assertThatThrownBy(() -> limits().storeCode("abcdef"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(12899))
				.hasMessageContaining("FIXTURE_LIMITS");
	}

	@Test
	@DisplayName("on the way out the same rules apply: a POSITIVE return value breaking its range is the routine's error,"
			+ " a VARCHAR2(3) subtype returns any size")
	void return_value() {
		assertThatThrownBy(() -> limits().retPositive())
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> assertThat(e.getCode()).isEqualTo(6502));
		assertThat(limits().retCode()).isEqualTo("abcdef");
	}
}
