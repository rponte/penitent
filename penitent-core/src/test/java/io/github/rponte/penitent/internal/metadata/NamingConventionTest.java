package io.github.rponte.penitent.internal.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NamingConventionTest {

	@Test
	@DisplayName("the camelCase -> SNAKE_CASE convention")
	void naming_convention() {
		assertThat(RoutineMetadata.toRoutineName("updateAgreementP")).isEqualTo("UPDATE_AGREEMENT_P");
		assertThat(RoutineMetadata.toRoutineName("updateStatusProcessP")).isEqualTo("UPDATE_STATUS_PROCESS_P");
		assertThat(RoutineMetadata.toRoutineName("processAllocationF")).isEqualTo("PROCESS_ALLOCATION_F");
	}
}
