package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * The object-relational {@code REF}, a pointer to a row of an object table, which Penitent does not map yet (#125):
 * an attribute or an element that is one is refused with a clear error, by {@code validate} and by the call alike. It
 * is not the object it points to, which is how it was read before, and a type can refer to itself only through one.
 * The fixtures are in {@code V32__object_refs.sql}.
 */
class ObjectRefsTest extends OracleIntegrationTest {

	record Node(BigDecimal id, Object parent) {
	}

	@PlsqlPackage("FIXTURE_REFS")
	interface Refs {
		Node echoNode(Node value);

		BigDecimal countRefs(List<Object> value);
	}

	@Test
	@DisplayName("an attribute that is a REF is refused by the call, even when null")
	void attribute_refused_by_the_call() {
		Refs refs = PlsqlPackages.create(Refs.class, DATA_SOURCE);

		assertThatThrownBy(() -> refs.echoNode(new Node(BigDecimal.ONE, null)))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PARENT of REF_NODE_T is a REF")
				.hasMessageContaining("which Penitent does not map");
	}

	@Test
	@DisplayName("validate refuses the same, with the same message")
	void refused_by_validate() {
		Refs refs = PlsqlPackages.create(Refs.class, DATA_SOURCE);

		assertThatThrownBy(() -> PlsqlPackages.validate(refs)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PARENT of REF_NODE_T is a REF")
				.hasMessageContaining("REF_NODE_LIST_T holds REFs");
	}

	@Test
	@DisplayName("a collection of REFs is refused by the call")
	void collection_refused_by_the_call() {
		Refs refs = PlsqlPackages.create(Refs.class, DATA_SOURCE);

		assertThatThrownBy(() -> refs.countRefs(List.of())).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("REF_NODE_LIST_T holds REFs")
				.hasMessageContaining("which Penitent does not map");
	}
}
