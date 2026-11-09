package io.github.rponte.penitent.internal.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.internal.metadata.CompositeType.Kind;

/** The name the driver addresses each form by: {@code DriverAddressingTest} shows why. */
class CompositeTypeTest {

	@Test
	@DisplayName("an OBJECT and a package RECORD: the owner, a dot and the name")
	void object_and_record() {
		assertThat(new CompositeType(Kind.OBJECT, "PLSQL_TEST", null, "ADDRESS_T", "ADDRESS_T").qualifiedBindName())
				.isEqualTo("PLSQL_TEST.ADDRESS_T");
		assertThat(new CompositeType(Kind.PLSQL_RECORD, "PLSQL_TEST", "PKG", "REC", "PKG.REC").qualifiedBindName())
				.isEqualTo("PLSQL_TEST.PKG.REC");
	}

	@Test
	@DisplayName("a %ROWTYPE: the owner in quotes, which is what driver 21 needs to keep the suffix")
	void rowtype() {
		assertThat(new CompositeType(Kind.ROWTYPE, "PLSQL_TEST", null, "FIXTURE_ITEM", "FIXTURE_ITEM%ROWTYPE")
				.qualifiedBindName()).isEqualTo("\"PLSQL_TEST\".FIXTURE_ITEM%ROWTYPE");
	}
}
