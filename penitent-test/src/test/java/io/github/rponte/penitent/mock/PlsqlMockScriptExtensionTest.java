package io.github.rponte.penitent.mock;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * {@code @PlsqlMockScript} with the JUnit 5 extension, without Spring: the scripts of the test class, then those of
 * the test method, applied in the test's edition when it begins, before {@code @BeforeEach}. Also run with Spring off
 * the classpath.
 */
class PlsqlMockScriptExtensionTest {

	@PlsqlPackage("FREIGHTS")
	public interface Freights {
		BigDecimal countViewItems(String pUf);
	}

	@PlsqlPackage("CHECKOUT")
	public interface Checkout {
		BigDecimal total(String pOrigin, String pDestination);
	}

	static final String CHECKOUT_999 = """
			CREATE OR REPLACE PACKAGE BODY checkout AS
			  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
			  BEGIN
			    RETURN 999;
			  END total;
			END checkout;
			/
			""";

	static final String ONE_ITEM = "CREATE OR REPLACE VIEW freight_items_v AS SELECT 7 AS id, 'SP' AS uf FROM dual;";

	private static DataSource pool;
	static DataSource dataSource;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		dataSource = PlsqlMocks.wrap(pool);
	}

	// ---------------------------------------------------------------- JUnit 5

	/** The class's script in every test, and a method's own script in that test only. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	static class ClassAndMethod {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		final Freights freights = PlsqlPackages.create(Freights.class, dataSource);
		final Checkout checkout = PlsqlPackages.create(Checkout.class, dataSource);

		@BeforeEach
		void sees_the_scripts_already() {
			assertThat(freights.countViewItems("SP")).isEqualByComparingTo("2");
		}

		@Test
		@Order(1)
		@PlsqlMockScript(scriptText = CHECKOUT_999)
		void with_its_own() {
			assertThat(freights.countViewItems("SP")).isEqualByComparingTo("2");
			assertThat(checkout.total("SP", "CE")).isEqualByComparingTo("999");
		}

		@Test
		@Order(2)
		void with_the_class_only() {
			assertThat(checkout.total("SP", "CE")).isNotEqualByComparingTo("999");
		}
	}

	@Test
	@DisplayName("JUnit 5: the class's scripts in every test, a method's in its own, all before @BeforeEach")
	void junit_class_and_method() {
		TestKit.run(ClassAndMethod.class).assertStatistics(stats -> stats.started(2).succeeded(2));

		assertThat(TestKit.editionsLeft(pool)).isZero();
	}

	/** The method's scripts after the class's, and repeated ones in their order: the last view wins. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	static class Order_ {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		@Test
		@PlsqlMockScript(scriptText = CHECKOUT_999)
		@PlsqlMockScript(scriptText = ONE_ITEM)
		void last_one_wins() {
			assertThat(PlsqlPackages.create(Freights.class, dataSource).countViewItems("SP")).isEqualByComparingTo("1");
			assertThat(PlsqlPackages.create(Checkout.class, dataSource).total("SP", "CE")).isEqualByComparingTo("999");
		}
	}

	@Test
	@DisplayName("the class's scripts first, then the method's, each annotation in its order")
	void order() {
		TestKit.run(Order_.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** An annotation that names no script. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class Empty {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		@Test
		@PlsqlMockScript
		void nothing() {
		}
	}

	@Test
	@DisplayName("an annotation with neither scripts nor scriptText is refused, saying where it is")
	void empty_refused() {
		Throwable failure = TestKit.firstFailure(TestKit.run(Empty.class));

		assertThat(failure).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("@PlsqlMockScript")
				.hasMessageContaining("nothing")
				.hasMessageContaining("scripts")
				.hasMessageContaining("scriptText");
		assertThat(TestKit.editionsLeft(pool)).isZero();
	}

	/** value and scripts are the same attribute: both, with different scripts, cannot both be meant. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class ValueAndScripts {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		@Test
		@PlsqlMockScript(value = "/mocks/freight-items-v.sql", scripts = "/mocks/freight-items-and-checkout.sql")
		void both() {
		}
	}

	@Test
	@DisplayName("value and scripts, which are the same attribute, with different scripts are refused")
	void value_and_scripts_refused() {
		assertThat(TestKit.firstFailure(TestKit.run(ValueAndScripts.class)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("value")
				.hasMessageContaining("scripts");
	}

	// ---------------------------------------------------------------- where the annotation is found

	/** The script on a superclass of the test class. */
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	abstract static class ViewBase {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class FromSuperclass extends ViewBase {

		@Test
		void sees_it() {
			assertThat(PlsqlPackages.create(Freights.class, dataSource).countViewItems("SP")).isEqualByComparingTo("2");
		}
	}

	@Test
	@DisplayName("JUnit 5: on a superclass of the test class")
	void junit_superclass() {
		TestKit.run(FromSuperclass.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** An annotation of the project's own, holding the script. */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ ElementType.TYPE, ElementType.METHOD })
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	@interface WithFreightItems {
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class AsMetaAnnotation {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		@Test
		@WithFreightItems
		void sees_it() {
			assertThat(PlsqlPackages.create(Freights.class, dataSource).countViewItems("SP")).isEqualByComparingTo("2");
		}
	}

	@Test
	@DisplayName("JUnit 5: as a meta-annotation, on an annotation of the project's own")
	void junit_meta_annotation() {
		TestKit.run(AsMetaAnnotation.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** The script on the class that encloses a @Nested one. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	static class Enclosing {

		@RegisterExtension
		static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

		@Nested
		class Inner {

			@Test
			void sees_it() {
				assertThat(PlsqlPackages.create(Freights.class, dataSource).countViewItems("SP"))
						.isEqualByComparingTo("2");
			}
		}
	}

	@Test
	@DisplayName("JUnit 5: on the class enclosing a @Nested test class")
	void junit_enclosing_class() {
		TestKit.run(Enclosing.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}
}
