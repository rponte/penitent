package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackages;

/**
 * How many dictionary queries the first call of a method costs, and that the next ones cost none.
 *
 * <p>The numbers are today's, not a goal. Each method reads its own {@code ALL_ARGUMENTS} (an N+1 per
 * interface), and some lookups repeat: the current schema and the package, once per method, and the
 * same collection more than once within a method. When that is optimized, lower them here: the point
 * is that a change in these costs is noticed, in either direction.
 */
class DictionaryQueriesTest extends OracleIntegrationTest {

	private final QueryCounter counter = new QueryCounter(DATA_SOURCE);

	@Test
	@DisplayName("a scalar routine: 5 queries on the first call, none after")
	void scalar() {
		assertQueries(ScalarsTest.Scalars.class, s -> s.plus(BigDecimal.ONE, BigDecimal.ONE), 5);
	}

	@Test
	@DisplayName("an OBJECT: one more query, for its attributes")
	void object() {
		assertQueries(ObjectTypesTest.Composites.class, s -> s.echoAddress(new ObjectTypesTest.Address("A", 1)), 6);
	}

	@Test
	@DisplayName("a collection of OBJECT: 12 queries, some of them repeated")
	void collection_of_objects() {
		assertQueries(CollectionsTest.Composites.class,
				s -> s.echoAddresses(List.of(new CollectionsTest.Address("A", 1))), 12);
	}

	@Test
	@DisplayName("an OBJECT with nested OBJECTs and collections: 16 queries")
	void nested_object() {
		assertQueries(ObjectTypesTest.Composites.class, s -> s.echoCustomer(customer()), 16);
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

	private <T> void assertQueries(Class<T> type, Consumer<T> call, long firstCall) {
		T proxy = PlsqlPackages.create(type, counter.dataSource());

		counter.reset();
		call.accept(proxy);
		assertThat(counter.dictionaryQueries()).as("first call").isEqualTo(firstCall);

		counter.reset();
		call.accept(proxy);
		assertThat(counter.dictionaryQueries()).as("second call").isZero();
	}

	private static ObjectTypesTest.Customer customer() {
		return new ObjectTypesTest.Customer(List.of(new ObjectTypesTest.Address("B", 2)), 7L, "Ann Doe", true, true,
				LocalDate.parse("1990-05-20"), ObjectTypesTest.Status.NEW, new ObjectTypesTest.Address("A", 1),
				new String[] { "Annie" });
	}
}
