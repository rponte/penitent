package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;

/**
 * The example of docs/compatibility.md. It runs on every driver and database the build uses, driver 23
 * against Oracle 21c included, where the same value as a {@code BOOLEAN} would lose its null: see
 * {@link NullBooleanOutputTest}.
 */
class DocsCompatibilityTest extends OracleIntegrationTest {

	@PlsqlPackage("CUSTOMER_STATUS_PKG")
	interface CustomerStatus {

		// snippet: compatibility/boolean-as-text
		@PlsqlRoutine("IS_ACTIVE_YN")
		Boolean isActive(long id); // null stays null
		// end-snippet
	}

	@Test
	@DisplayName("a BOOLEAN handed back as VARCHAR2 reads into a Boolean, null included")
	void boolean_as_text() {
		CustomerStatus status = pkg(CustomerStatus.class);

		assertThat(status.isActive(1)).isTrue();
		assertThat(status.isActive(2)).isFalse();
		assertThat(status.isActive(3)).isNull();
	}
}
