package io.github.rponte.penitent.mock;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import io.github.rponte.penitent.mock.spring.EnablePlsqlMocks;

/**
 * {@code @PlsqlMockScript} with {@code @EnablePlsqlMocks}: applied once the test's edition begins, before Spring's test
 * transaction, so it commits nothing of it; and refused without {@code @EnablePlsqlMocks}, rather than ignored.
 */
class PlsqlMockScriptSpringTest {

	static final String CHECKOUT_999 = """
			CREATE OR REPLACE PACKAGE BODY checkout AS
			  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
			  BEGIN
			    RETURN 999;
			  END total;
			END checkout;
			/
			""";

	private static DataSource pool;

	@BeforeAll
	static void startDatabase() {
		pool = TestDatabase.pool();
		// the Spring samples take EnablePlsqlMocksTest's application, whose DataSource is that test's pool
		EnablePlsqlMocksTest.pool = pool;
	}

	// ---------------------------------------------------------------- Spring

	/** A transactional test: the script commits nothing of it. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(EnablePlsqlMocksTest.Application.class)
	@EnablePlsqlMocks
	@Transactional
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	static class SpringTransactional {

		@Autowired JdbcTemplate jdbc;

		@Test
		@PlsqlMockScript(scriptText = CHECKOUT_999)
		void sees_both() {
			jdbc.update("INSERT INTO freight_item (id, uf) VALUES (9400, 'SP')");

			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freight_items_v WHERE uf = 'SP'", Integer.class))
					.isEqualTo(2);
			assertThat(jdbc.queryForObject("SELECT checkout.total('SP', 'CE') FROM dual", Integer.class)).isEqualTo(999);
		}
	}

	@Test
	@DisplayName("Spring: the scripts are applied in the test's edition, and commit nothing of the test's transaction")
	void spring_transactional() {
		TestKit.run(SpringTransactional.class).assertStatistics(stats -> stats.started(1).succeeded(1));

		assertThat(new JdbcTemplate(pool).queryForObject("SELECT COUNT(*) FROM freight_item WHERE id = 9400",
				Integer.class)).as("rolled back with the test").isZero();
		assertThat(TestKit.editionsLeft(pool)).isZero();
	}

	/** The annotation, with no mocks to apply it. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(EnablePlsqlMocksTest.Application.class)
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	static class SpringWithoutMocks {

		@Test
		void runs() {
		}
	}

	@Test
	@DisplayName("Spring: the annotation without @EnablePlsqlMocks is refused, rather than ignored")
	void spring_without_mocks_refused() {
		assertThat(TestKit.firstFailure(TestKit.run(SpringWithoutMocks.class)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("@PlsqlMockScript")
				.hasMessageContaining("@EnablePlsqlMocks");
	}

	// ---------------------------------------------------------------- where the annotation is found

	/** The script on a superclass of the test class. */
	@SpringJUnitConfig(EnablePlsqlMocksTest.Application.class)
	@EnablePlsqlMocks
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	abstract static class SpringViewBase {

		@Autowired JdbcTemplate jdbc;

		int items() {
			return jdbc.queryForObject("SELECT COUNT(*) FROM freight_items_v WHERE uf = 'SP'", Integer.class);
		}
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	static class SpringFromSuperclass extends SpringViewBase {

		@Test
		void sees_it() {
			assertThat(items()).isEqualTo(2);
		}
	}

	@Test
	@DisplayName("Spring: on a superclass of the test class")
	void spring_superclass() {
		TestKit.run(SpringFromSuperclass.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** An annotation of the project's own, holding the script. */
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ ElementType.TYPE, ElementType.METHOD })
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	@interface WithFreightItems {
	}

	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(EnablePlsqlMocksTest.Application.class)
	@EnablePlsqlMocks
	static class SpringAsMetaAnnotation {

		@Autowired JdbcTemplate jdbc;

		@Test
		@WithFreightItems
		void sees_it() {
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freight_items_v WHERE uf = 'SP'", Integer.class))
					.isEqualTo(2);
		}
	}

	@Test
	@DisplayName("Spring: as a meta-annotation, on an annotation of the project's own")
	void spring_meta_annotation() {
		TestKit.run(SpringAsMetaAnnotation.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}

	/** The script on the class that encloses a @Nested one. */
	@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
	@SpringJUnitConfig(EnablePlsqlMocksTest.Application.class)
	@EnablePlsqlMocks
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	static class SpringEnclosing {

		@Autowired JdbcTemplate jdbc;

		@Nested
		class Inner {

			@Test
			void sees_it() {
				assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM freight_items_v WHERE uf = 'SP'", Integer.class))
						.isEqualTo(2);
			}
		}
	}

	@Test
	@DisplayName("Spring: on the class enclosing a @Nested test class")
	void spring_enclosing_class() {
		TestKit.run(SpringEnclosing.class).assertStatistics(stats -> stats.started(1).succeeded(1));
	}
}
