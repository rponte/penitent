package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBoolean;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * A JavaBean's boolean property read through {@code isX()}: {@code ACTIVE} is a {@code VARCHAR2(1)}, {@code Y} and
 * {@code N} by default, and {@code VIP} a {@code NUMBER(1)}, {@code 1} and {@code 0}.
 */
class JavaBeanBooleanTest extends OracleIntegrationTest {

	/** {@code isX()} on a primitive {@code boolean} and on a {@code Boolean}. */
	public static class Flags {
		private boolean active;
		private Boolean vip;

		public boolean isActive() {
			return active;
		}

		public void setActive(boolean active) {
			this.active = active;
		}

		public Boolean isVip() {
			return vip;
		}

		public void setVip(Boolean vip) {
			this.vip = vip;
		}
	}

	public static class SnOnField {
		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		private boolean active;

		public boolean isActive() {
			return active;
		}

		public void setActive(boolean active) {
			this.active = active;
		}
	}

	public static class SnOnGetter {
		private boolean active;

		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		public boolean isActive() {
			return active;
		}

		public void setActive(boolean active) {
			this.active = active;
		}
	}

	public static class SnOnSetter {
		private boolean active;

		public boolean isActive() {
			return active;
		}

		@PlsqlBoolean(trueValue = "S", falseValue = "N")
		public void setActive(boolean active) {
			this.active = active;
		}
	}

	/** Two readers of {@code active}, which may answer differently: the library cannot tell which one is meant. */
	public static class TwoReaders {
		private Boolean active;

		public boolean isActive() {
			return Boolean.TRUE.equals(active);
		}

		public Boolean getActive() {
			return active;
		}

		public void setActive(Boolean active) {
			this.active = active;
		}
	}

	@PlsqlPackage("FIXTURE_BEAN_FLAGS")
	interface BeanFlags {
		Flags echoFlags(Flags value);

		String flagsActive(Flags value);

		String activeOf(SnOnField value);

		String activeOf(SnOnGetter value);

		String activeOf(SnOnSetter value);

		String activeOf(TwoReaders value);

		@PlsqlRoutine("ACTIVE_WITH")
		SnOnField snOnFieldWith(String active);

		@PlsqlRoutine("ACTIVE_WITH")
		SnOnGetter snOnGetterWith(String active);

		@PlsqlRoutine("ACTIVE_WITH")
		SnOnSetter snOnSetterWith(String active);
	}

	private final BeanFlags flags = pkg(BeanFlags.class);

	private static Flags flags(boolean active, Boolean vip) {
		Flags flags = new Flags();
		flags.setActive(active);
		flags.setVip(vip);
		return flags;
	}

	@Test
	@DisplayName("isX() writes a boolean property as the dictionary's literals: Y and N in a VARCHAR2")
	void is_getter_on_write() {
		assertThat(flags.flagsActive(flags(true, null))).isEqualTo("Y");
		assertThat(flags.flagsActive(flags(false, null))).isEqualTo("N");
	}

	@Test
	@DisplayName("isX() on a primitive boolean and on a Boolean, in a VARCHAR2 and a NUMBER(1), read back by the setters")
	void round_trip() {
		Flags echo = flags.echoFlags(flags(true, false));
		assertThat(echo.isActive()).isTrue();
		assertThat(echo.isVip()).isFalse();

		echo = flags.echoFlags(flags(false, true));
		assertThat(echo.isActive()).isFalse();
		assertThat(echo.isVip()).isTrue();
		assertThat(flags.echoFlags(flags(false, null)).isVip()).isNull();
	}

	@Test
	@DisplayName("@PlsqlBoolean on the field, the isX() getter or the setter, on write")
	void literals_anywhere_on_write() {
		SnOnField onField = new SnOnField();
		onField.setActive(true);
		SnOnGetter onGetter = new SnOnGetter();
		onGetter.setActive(true);
		SnOnSetter onSetter = new SnOnSetter();
		onSetter.setActive(false);

		assertThat(flags.activeOf(onField)).isEqualTo("S");
		assertThat(flags.activeOf(onGetter)).isEqualTo("S");
		assertThat(flags.activeOf(onSetter)).isEqualTo("N");
	}

	@Test
	@DisplayName("@PlsqlBoolean on the field, the isX() getter or the setter, on read")
	void literals_anywhere_on_read() {
		assertThat(flags.snOnFieldWith("S").isActive()).isTrue();
		assertThat(flags.snOnGetterWith("S").isActive()).isTrue();
		assertThat(flags.snOnSetterWith("N").isActive()).isFalse();
	}

	@Test
	@DisplayName("isX() and getX() on the same property are refused, naming both")
	void two_readers() {
		TwoReaders value = new TwoReaders();
		value.setActive(null);

		assertThatThrownBy(() -> flags.activeOf(value))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("TwoReaders: getActive() and isActive() both read the property active");
	}
}
