package io.github.rponte.penitent.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlSettings;

/**
 * The {@code penitent.*} properties. They apply to the {@link PlsqlSettings} the starter builds, and so
 * not to a {@code PlsqlSettings} bean declared by the application, which wins, as with any Spring
 * Boot autoconfiguration.
 */
@ConfigurationProperties("penitent")
public class PlsqlProperties {

	/**
	 * How the interfaces that do not declare one bind their parameters: BY_POSITION or BY_NAME. Unset
	 * means BY_POSITION.
	 */
	private PlsqlBind bind;

	private final Validation validation = new Validation();

	public PlsqlBind getBind() {
		return bind;
	}

	/** {@code DEFAULT} means "use the settings", and this is the settings: it cannot point to itself. */
	public void setBind(PlsqlBind bind) {
		if (bind == PlsqlBind.DEFAULT) {
			throw new IllegalArgumentException("penitent.bind must be BY_POSITION or BY_NAME, not DEFAULT");
		}
		this.bind = bind;
	}

	public Validation getValidation() {
		return validation;
	}

	/** Validation of the interfaces against the database at startup. */
	public static class Validation {

		/**
		 * Whether every registered interface is checked against the database when the context starts.
		 * A mismatch stops the context. With it on, the application does not start without the
		 * database.
		 */
		private boolean enabled;

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}
	}
}
