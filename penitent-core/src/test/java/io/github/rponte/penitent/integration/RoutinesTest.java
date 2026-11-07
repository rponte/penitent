package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlParam;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/** How the database object is found, beyond the plain package in the session schema. */
class RoutinesTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_SYNONYM")
	interface BySynonym {
		int constant();
	}

	@PlsqlPackage("\"fixtureLowercase\"")
	interface WithQuotedName {
		int constant();
	}

	/** Unquoted, as in SQL, the name is read in upper case: FIXTURELOWERCASE. */
	@PlsqlPackage("fixtureLowercase")
	interface WithUnquotedName {
		int constant();
	}

	@PlsqlPackage("FIXTURE_INVALID")
	interface InvalidPackage {
		int constant();
	}

	@PlsqlSchema
	interface OutsidePackage {
		int fixtureStandaloneFunction(int value);

		void fixtureStandaloneProcedure(int value, PlsqlOut<Integer> result);

		void fixtureStandaloneNoop();

		String fixtureStandaloneConcat(String first);

		@PlsqlRoutine("FIXTURE_STANDALONE_SYNONYM")
		int viaSynonym(int value);

		int doesNotExist(int value);

		@PlsqlRoutine("FIXTURE_SCALARS")
		int packageAsRoutine();
	}

	@PlsqlSchema(bind = PlsqlBind.BY_NAME)
	interface OutsidePackageByName {
		String fixtureStandaloneConcat(@PlsqlParam("p_second") String second, @PlsqlParam("p_first") String first);

		@PlsqlRoutine("FIXTURE_STANDALONE_CONCAT")
		String onlyFirst(String pFirst);
	}

	@PlsqlSchema("plsql_test")
	interface OutsidePackageInSchema {
		int fixtureStandaloneFunction(int value);
	}

	@PlsqlPackage("")
	interface EmptyPackageName {
		int fixtureStandaloneFunction(int value);
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	@PlsqlSchema
	interface TwoContainers {
		int constant();
	}

	/** Blocks need no container, and may sit next to the routines of a schema. */
	@PlsqlSchema
	interface SchemaWithBlock {
		int fixtureStandaloneFunction(int value);

		@PlsqlBlock("begin :result := :value * 2; end;")
		int doubled(int value);
	}

	interface OutsidePackageViaBlock {
		@PlsqlBlock("begin :result := fixture_standalone_function(:value); end;")
		int call(int value);
	}

	@Test
	@DisplayName("a package with an invalid body arrives as ORA-04063 in the cause")
	void invalid_package() {
		assertThatThrownBy(() -> pkg(InvalidPackage.class).constant())
				.cause()
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("ORA-04063");
	}

	@Test
	@DisplayName("standalone function, worked around with @PlsqlBlock")
	void standalone_function_via_block() {
		assertThat(pkg(OutsidePackageViaBlock.class).call(7)).isEqualTo(7);
	}

	@Test
	@DisplayName("package accessed through a synonym")
	void synonym() {
		assertThat(pkg(BySynonym.class).constant()).isEqualTo(42);
	}

	@Test
	@DisplayName("package with a quoted name keeps its case, as in SQL")
	void quoted_name() {
		assertThat(pkg(WithQuotedName.class).constant()).isEqualTo(42);
	}

	@Test
	@DisplayName("package name without quotes is read in upper case, and that package does not exist")
	void unquoted_lowercase_name() {
		assertThatThrownBy(() -> pkg(WithUnquotedName.class).constant())
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("FIXTURELOWERCASE")
				.hasMessageContaining("does not exist or is not visible");
	}

	@Test
	@DisplayName("standalone function")
	void standalone_function() {
		assertThat(pkg(OutsidePackage.class).fixtureStandaloneFunction(7)).isEqualTo(7);
	}

	@Test
	@DisplayName("standalone procedure, with an OUT parameter and without parameters")
	void standalone_procedure() {
		OutsidePackage outside = pkg(OutsidePackage.class);
		PlsqlOut<Integer> result = new PlsqlOut<>();

		outside.fixtureStandaloneProcedure(21, result);
		outside.fixtureStandaloneNoop();

		assertThat(result.get()).isEqualTo(42);
	}

	@Test
	@DisplayName("standalone function with a DEFAULT parameter omitted, and through a synonym")
	void standalone_default_and_synonym() {
		OutsidePackage outside = pkg(OutsidePackage.class);

		assertThat(outside.fixtureStandaloneConcat("a")).isEqualTo("az");
		assertThat(outside.viaSynonym(7)).isEqualTo(7);
	}

	@Test
	@DisplayName("standalone routine bound by name")
	void standalone_by_name() {
		OutsidePackageByName outside = pkg(OutsidePackageByName.class);

		assertThat(outside.fixtureStandaloneConcat("b", "a")).isEqualTo("ab");
		assertThat(outside.onlyFirst("a")).isEqualTo("az");
	}

	@Test
	@DisplayName("unknown standalone routine, or a package where a routine is expected, says it does not exist")
	void unknown_standalone_routine() {
		OutsidePackage outside = pkg(OutsidePackage.class);

		assertThatThrownBy(() -> outside.doesNotExist(1))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("standalone routine PLSQL_TEST.DOES_NOT_EXIST does not exist or is not visible");
		assertThatThrownBy(outside::packageAsRoutine)
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("standalone routine PLSQL_TEST.FIXTURE_SCALARS does not exist");
	}

	@Test
	@DisplayName("@PlsqlSchema with an explicit schema, in any case")
	void standalone_in_explicit_schema() {
		assertThat(pkg(OutsidePackageInSchema.class).fixtureStandaloneFunction(7)).isEqualTo(7);
	}

	@Test
	@DisplayName("a block can sit next to the routines of a schema")
	void schema_with_block() {
		SchemaWithBlock schema = pkg(SchemaWithBlock.class);

		assertThat(schema.fixtureStandaloneFunction(7)).isEqualTo(7);
		assertThat(schema.doubled(7)).isEqualTo(14);
	}

	@Test
	@DisplayName("an empty package name is an error that points to @PlsqlSchema, when the proxy is created")
	void empty_package_name() {
		assertThatThrownBy(() -> pkg(EmptyPackageName.class))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declares @PlsqlPackage without a name; for the routines outside a package,"
						+ " use @PlsqlSchema");
	}

	@Test
	@DisplayName("an interface with two containers is an error, when the proxy is created")
	void two_containers() {
		assertThatThrownBy(() -> pkg(TwoContainers.class))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("declares both @PlsqlPackage and @PlsqlSchema");
	}
}
