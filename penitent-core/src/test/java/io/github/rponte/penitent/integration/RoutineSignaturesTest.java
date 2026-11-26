package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlRoutine;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.metadata.Dictionary;
import io.github.rponte.penitent.internal.metadata.RoutineSignatures;

/**
 * The signatures of a package interface's routines are read in one query, on the first call of any of its methods,
 * and kept while the proxy lives (#169). Each method is still described and checked when it is first used, so a
 * method that does not fit fails only when it is called. A spec changed after they were read fails the first call
 * of a method it affects, without running it, and the next call uses the new signature.
 */
class RoutineSignaturesTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_SCALARS")
	interface WithAMethodThatDoesNotFit {
		BigDecimal plus(BigDecimal a, BigDecimal b);

		@PlsqlRoutine("NO_SUCH_ROUTINE")
		BigDecimal missing(BigDecimal value);
	}

	@PlsqlPackage("SIG_CHANGED")
	interface Changed {
		BigDecimal f(BigDecimal pA);

		BigDecimal g(BigDecimal pA, BigDecimal pB);
	}

	private static void ddl(String... statements) {
		try (Connection c = DATA_SOURCE.getConnection(); Statement s = c.createStatement()) {
			for (String sql : statements) {
				s.execute(sql);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	private static long argumentQueries(QueryCounter counter) {
		return counter.statements().stream().filter(s -> s.toLowerCase(Locale.ROOT).contains("all_arguments")).count();
	}

	@Test
	@DisplayName("after the first call of one method, the first call of another reads nothing from the dictionary")
	void read_once_for_the_interface() {
		QueryCounter counter = new QueryCounter(DATA_SOURCE);
		ScalarsTest.Scalars scalars = PlsqlPackages.create(ScalarsTest.Scalars.class, counter.dataSource());
		scalars.plus(BigDecimal.ONE, BigDecimal.ONE);
		counter.reset();

		assertThat(scalars.echoText("x")).isEqualTo("x");
		assertThat(scalars.echoInteger(7)).isEqualTo(7);

		assertThat(counter.dictionaryQueries()).isZero();
	}

	@Test
	@DisplayName("a method that does not fit fails only when it is called, and the others work")
	void a_method_that_does_not_fit() {
		WithAMethodThatDoesNotFit proxy = PlsqlPackages.create(WithAMethodThatDoesNotFit.class, DATA_SOURCE);

		assertThat(proxy.plus(BigDecimal.ONE, BigDecimal.ONE)).isEqualByComparingTo("2");
		assertThatThrownBy(() -> proxy.missing(BigDecimal.ONE)).isInstanceOf(PlsqlMappingException.class)
				.hasMessageContaining("NO_SUCH_ROUTINE does not exist. Available: ");
		assertThat(proxy.plus(BigDecimal.ONE, BigDecimal.TEN)).isEqualByComparingTo("11");
	}

	@Test
	@DisplayName("more routines than an IN list takes, 1000, are read in chunks")
	void more_than_1000_routines() {
		List<String> names = IntStream.rangeClosed(1, 1100).mapToObj(i -> String.format("F%04d", i)).toList();
		StringBuilder spec = new StringBuilder("CREATE OR REPLACE PACKAGE sig_many AS\n");
		names.forEach(n -> spec.append("FUNCTION ").append(n).append("(p_a NUMBER) RETURN NUMBER;\n"));
		ddl(spec.append("END;").toString());
		try (Connection c = DATA_SOURCE.getConnection()) {
			RoutineSignatures signatures = new Dictionary(c).signatures(OracleUnderTest.USER.toUpperCase(Locale.ROOT),
					"SIG_MANY", names);

			assertThat(names).allSatisfy(n -> assertThat(signatures.of(n)).isPresent());
			assertThat(signatures.of("F1100").orElseThrow().versions().values().iterator().next())
					.extracting(a -> a.name()).containsExactly(null, "P_A");
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		} finally {
			ddl("DROP PACKAGE sig_many");
		}
	}

	@Test
	@DisplayName("first calls of many methods at once: each right, and the signatures read at most once per thread")
	void concurrent_first_calls() throws Exception {
		QueryCounter counter = new QueryCounter(DATA_SOURCE);
		ScalarsTest.Scalars scalars = PlsqlPackages.create(ScalarsTest.Scalars.class, counter.dataSource());
		List<Function<ScalarsTest.Scalars, Object>> calls = List.of(s -> s.plus(BigDecimal.ONE, BigDecimal.ONE),
				s -> s.echoText("x"), s -> s.echoInteger(7), s -> s.echoLong(8L), s -> s.echoDouble(1.5),
				s -> s.constant(), s -> s.echoPrimitive(9), s -> s.booleanAsText(true));
		List<Object> expected = List.of(new BigDecimal("2"), "x", 7, 8L, 1.5, 42, 9, "Y");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService threads = Executors.newFixedThreadPool(calls.size());
		try {
			List<Future<Object>> results = new ArrayList<>();
			for (Function<ScalarsTest.Scalars, Object> call : calls) {
				results.add(threads.submit(() -> {
					start.await();
					return call.apply(scalars);
				}));
			}
			start.countDown();
			for (int i = 0; i < calls.size(); i++) {
				Object result = results.get(i).get(60, TimeUnit.SECONDS);
				if (result instanceof BigDecimal number) {
					assertThat(number).isEqualByComparingTo((BigDecimal) expected.get(i));
				} else {
					assertThat(result).isEqualTo(expected.get(i));
				}
			}
		} finally {
			threads.shutdownNow();
		}
		assertThat(argumentQueries(counter)).isBetween(1L, (long) calls.size());

		counter.reset();
		scalars.echoEnum(ScalarsTest.Color.GREEN);
		scalars.textAsBoolean("Y");
		assertThat(counter.dictionaryQueries()).as("the methods not called yet read nothing").isZero();
	}

	@Test
	@DisplayName("a spec changed after the signatures were read fails a method's first call, which says so, and the next works")
	void spec_changed_after_the_read() {
		ddl("""
				CREATE OR REPLACE PACKAGE sig_changed AS
				  FUNCTION f(p_a NUMBER) RETURN NUMBER;
				  FUNCTION g(p_a NUMBER, p_b NUMBER) RETURN NUMBER;
				END;""", """
				CREATE OR REPLACE PACKAGE BODY sig_changed AS
				  FUNCTION f(p_a NUMBER) RETURN NUMBER IS BEGIN RETURN p_a; END;
				  FUNCTION g(p_a NUMBER, p_b NUMBER) RETURN NUMBER IS BEGIN RETURN p_a - p_b; END;
				END;""");
		try {
			Changed changed = PlsqlPackages.create(Changed.class, DATA_SOURCE);
			assertThat(changed.f(BigDecimal.ONE)).isEqualByComparingTo("1");

			// g's parameters renamed, as a deploy under the running application would do
			ddl("""
					CREATE OR REPLACE PACKAGE sig_changed AS
					  FUNCTION f(p_a NUMBER) RETURN NUMBER;
					  FUNCTION g(p_x NUMBER, p_y NUMBER) RETURN NUMBER;
					END;""", """
					CREATE OR REPLACE PACKAGE BODY sig_changed AS
					  FUNCTION f(p_a NUMBER) RETURN NUMBER IS BEGIN RETURN p_a; END;
					  FUNCTION g(p_x NUMBER, p_y NUMBER) RETURN NUMBER IS BEGIN RETURN p_x - p_y; END;
					END;""");

			assertThatThrownBy(() -> changed.g(BigDecimal.TEN, BigDecimal.ONE))
					.isInstanceOf(PlsqlExecutionException.class)
					.hasMessageContaining("SIG_CHANGED.G changed since this proxy described it, and was described again");
			assertThat(changed.g(BigDecimal.TEN, BigDecimal.ONE)).isEqualByComparingTo("9");
		} finally {
			ddl("DROP PACKAGE sig_changed");
		}
	}
}
