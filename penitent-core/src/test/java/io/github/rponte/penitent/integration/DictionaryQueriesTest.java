package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlPackages;

/**
 * What describing routines costs, in dictionary queries and round trips to the database (#169): the first call of a
 * method, which describes it, and the validation of a whole interface, which describes every method. The next calls
 * cost none.
 *
 * <p>The numbers are today's, not a goal, and any change fails, in either direction: a change meant to lower them
 * updates them here, so its pull request shows the numbers before and after, and a change that raises them by
 * accident is noticed. The round trips are the measured session's own, read on an administrator's connection; where
 * they cannot be read, as on the 19c image, only the queries are checked. Time is not checked here, since it varies:
 * {@link DescribeTimingReport} reports it.
 */
class DictionaryQueriesTest extends OracleIntegrationTest {

	private final QueryCounter counter = new QueryCounter(DATA_SOURCE);

	private static HikariDataSource pool;
	private static DictionaryProfiler profiler;

	@BeforeAll
	static void measuredConnection() throws SQLException {
		pool = newPool();
		profiler = new DictionaryProfiler(pool, adminConnection());
	}

	@AfterAll
	static void close() throws SQLException {
		profiler.close();
		pool.close();
	}

	/**
	 * The cost of {@code action} on a new proxy, as the queries and round trips it took. It runs once before, on
	 * another proxy: the driver keeps the descriptors of the types it used in a cache of its connection, which the
	 * first use fills with round trips of its own, so only a connection that has seen the types gives the same
	 * number every time, as a pooled connection in an application does.
	 */
	private static <T> void assertCost(Class<T> type, Function<T, Object> action, int queries, int roundTrips) {
		run(PlsqlPackages.create(type, profiler.dataSource()), action);
		T proxy = PlsqlPackages.create(type, profiler.dataSource());
		DictionaryProfiler.Cost cost = profiler.measure(() -> run(proxy, action));
		if (cost.roundTrips() < 0) {
			assertThat(cost.count()).as("dictionary queries: %s", cost).isEqualTo(queries);
		} else {
			assertThat(cost.count() + " queries, " + cost.roundTrips() + " round trips").as("%s", cost)
					.isEqualTo(queries + " queries, " + roundTrips + " round trips");
		}
	}

	private static <T> Object run(T proxy, Function<T, Object> action) {
		try {
			return action.apply(proxy);
		} catch (RuntimeException e) {
			// a test interface may hold a method that does not fit on purpose: its cost counts all the same
			return e;
		}
	}

	private static Object validate(Object proxy) {
		PlsqlPackages.validate(proxy);
		return null;
	}

	@Test
	@DisplayName("the first call of a scalar routine")
	void scalar() {
		assertCost(ScalarsTest.Scalars.class, s -> s.plus(BigDecimal.ONE, BigDecimal.ONE), 4, 5);
	}

	@Test
	@DisplayName("the first call of a routine that takes an OBJECT: one more query, for its attributes")
	void object() {
		assertCost(ObjectTypesTest.Composites.class, s -> s.echoAddress(new ObjectTypesTest.Address("A", 1)), 5, 6);
	}

	@Test
	@DisplayName("the first call of a routine that takes a collection of OBJECT")
	void collection_of_objects() {
		assertCost(CollectionsTest.Composites.class,
				s -> s.echoAddresses(List.of(new CollectionsTest.Address("A", 1))), 11, 12);
	}

	@Test
	@DisplayName("the first call of a routine that takes an OBJECT with nested OBJECTs and collections")
	void nested_object() {
		assertCost(ObjectTypesTest.Composites.class, s -> s.echoCustomer(customer()), 15, 16);
	}

	@Test
	@DisplayName("validating an interface of 24 scalar methods")
	void validate_scalars() {
		assertCost(ScalarsTest.Scalars.class, DictionaryQueriesTest::validate, 50, 50);
	}

	@Test
	@DisplayName("validating an interface of 33 methods with OUT parameters and composites")
	void validate_outputs() {
		assertCost(OutParametersTest.Outputs.class, DictionaryQueriesTest::validate, 82, 82);
	}

	@Test
	@DisplayName("validating an interface of 36 methods over overloaded routines: more rows, so more round trips")
	void validate_overloads() {
		assertCost(OverloadingTest.Overload.class, DictionaryQueriesTest::validate, 78, 85);
	}

	@Test
	@DisplayName("validating an interface of 38 methods")
	void validate_time_zones() {
		assertCost(TimeZoneTest.TimeZones.class, DictionaryQueriesTest::validate, 84, 84);
	}

	@Test
	@DisplayName("after validate, a call reads nothing from the dictionary")
	void validate_warms_the_cache() {
		ObjectTypesTest.Composites composites = PlsqlPackages.create(ObjectTypesTest.Composites.class,
				counter.dataSource());
		PlsqlPackages.validate(composites);
		counter.reset();

		composites.echoCustomer(customer());
		composites.echoAddress(new ObjectTypesTest.Address("A", 1));
		composites.customerName(customer());

		assertThat(counter.dictionaryQueries()).isZero();
	}

	@Test
	@DisplayName("the cache belongs to each instance: a second create of the same interface reads the dictionary again")
	void cache_per_instance() {
		ScalarsTest.Scalars first = PlsqlPackages.create(ScalarsTest.Scalars.class, counter.dataSource());
		first.plus(BigDecimal.ONE, BigDecimal.ONE);

		ScalarsTest.Scalars second = PlsqlPackages.create(ScalarsTest.Scalars.class, counter.dataSource());
		counter.reset();
		second.plus(BigDecimal.ONE, BigDecimal.ONE);

		assertThat(counter.dictionaryQueries()).isEqualTo(4);
	}

	@Test
	@DisplayName("the next call of a method reads nothing")
	void next_call() {
		ScalarsTest.Scalars scalars = PlsqlPackages.create(ScalarsTest.Scalars.class, counter.dataSource());
		scalars.plus(BigDecimal.ONE, BigDecimal.ONE);
		counter.reset();
		scalars.plus(BigDecimal.ONE, BigDecimal.ONE);

		assertThat(counter.dictionaryQueries()).isZero();
	}

	private static ObjectTypesTest.Customer customer() {
		return new ObjectTypesTest.Customer(List.of(new ObjectTypesTest.Address("B", 2)), 7L, "Ann Doe", true, true,
				LocalDate.parse("1990-05-20"), ObjectTypesTest.Status.NEW, new ObjectTypesTest.Address("A", 1),
				new String[] { "Annie" });
	}
}
