package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * A package in another schema, reached through a synonym or the explicit schema: the case where the
 * application user is not the owner. The types also belong to the other schema, and that is what
 * this test proves: finding the package is not enough if the type name is resolved in the session's
 * schema.
 */
class OtherSchemaTest extends OracleIntegrationTest {

	record Address(String street, Integer num) {
	}

	record WithAddress(Long id, Address address, List<String> names) {
	}

	record Entry(Long id, String description) {
	}

	interface Owner {
		int constant();

		Address echoAddress(Address value);

		List<Address> echoAddresses(List<Address> value);

		List<String> echoNames(List<String> value);

		WithAddress echoNested(WithAddress value);

		Entry echoEntry(Entry value);

		List<Entry> echoEntries(List<Entry> value);

		Entry echoRowtype(Entry value);
	}

	@PlsqlPackage("FIXTURE_OWNER_SYNONYM")
	interface ByPrivateSynonym extends Owner {
	}

	@PlsqlPackage("FIXTURE_SYNONYM_OF_SYNONYM")
	interface BySynonymOfSynonym extends Owner {
	}

	@PlsqlPackage("FIXTURE_OWNER_PUBLIC")
	interface ByPublicSynonym extends Owner {
	}

	@PlsqlPackage(value = "FIXTURE_OF_OWNER", schema = "plsql_owner")
	interface ByExplicitSchema extends Owner {
	}

	@PlsqlPackage(value = "FIXTURE_OWNER_PUBLIC", schema = "plsql_owner")
	interface PublicSynonymWithSchema {
		int constant();
	}

	private static final Address ADDRESS = new Address("Street A", 1);

	private static void exercisesEverything(Owner owner) {
		assertThat(owner.constant()).isEqualTo(42);
		assertThat(owner.echoAddress(ADDRESS)).isEqualTo(ADDRESS);
		assertThat(owner.echoAddresses(List.of(ADDRESS))).containsExactly(ADDRESS);
		assertThat(owner.echoNames(List.of("a"))).containsExactly("a");

		WithAddress nested = new WithAddress(1L, ADDRESS, List.of("a"));
		assertThat(owner.echoNested(nested)).isEqualTo(nested);

		Entry entry = new Entry(1L, "one");
		assertThat(owner.echoEntry(entry)).isEqualTo(entry);
		assertThat(owner.echoEntries(List.of(entry))).containsExactly(entry);
		assertThat(owner.echoRowtype(entry)).isEqualTo(entry);
	}

	@Test
	@DisplayName("private synonym, with types from the other schema")
	void private_synonym() {
		exercisesEverything(pkg(ByPrivateSynonym.class));
	}

	@Test
	@DisplayName("synonym of a synonym")
	void synonym_of_synonym() {
		exercisesEverything(pkg(BySynonymOfSynonym.class));
	}

	@Test
	@DisplayName("public synonym")
	void public_synonym() {
		exercisesEverything(pkg(ByPublicSynonym.class));
	}

	@Test
	@DisplayName("explicit schema, without a synonym")
	void explicit_schema() {
		exercisesEverything(pkg(ByExplicitSchema.class));
	}

	@Test
	@DisplayName("with an explicit schema, the public synonym does not apply, as in Oracle")
	void public_synonym_with_schema() {
		assertThatThrownBy(() -> pkg(PublicSynonymWithSchema.class).constant())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("PLSQL_OWNER.FIXTURE_OWNER_PUBLIC");
	}
}
