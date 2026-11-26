package io.github.rponte.penitent.integration;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlPackages;

/**
 * How long describing routines takes (#169): the first call of a method, and the validation of a whole interface,
 * each on a new proxy, many times, reported as the median, p90, p95 and p99, beside the queries and round trips that
 * {@link DictionaryQueriesTest} pins. A report, not a check: time varies with the machine, so it only runs when asked.
 *
 * <pre>
 * ./mvnw -pl penitent-core verify -Dtest=DescribeTimingReport -Dpenitent.describe.report=true \
 *     [-Dpenitent.describe.runs=100]
 * </pre>
 *
 * A local test database hides the network, and a round trip is what a query per method multiplies, so the report also
 * models the median at 1 ms and 5 ms per round trip: the measured median plus the round trips times that latency.
 * Simulating the latency in the run does not work: a database in a local container is slower after each short pause,
 * which would be measured as well. The table goes to the output and to {@code target/describe-timing.md}, ready for a
 * pull request.
 */
class DescribeTimingReport extends OracleIntegrationTest {

	private static final int RUNS = Integer.getInteger("penitent.describe.runs", 100);

	private record Row(String scenario, DictionaryProfiler.Cost cost, double[] millis) {
	}

	private static double percentile(double[] sorted, double p) {
		int index = (int) Math.ceil(p / 100 * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
	}

	private static <T> Row scenario(DictionaryProfiler profiler, String name, Class<T> type, Function<T, Object> action) {
		double[] millis = new double[RUNS];
		DictionaryProfiler.Cost last = null;
		for (int run = -1; run < RUNS; run++) {
			T proxy = PlsqlPackages.create(type, profiler.dataSource());
			DictionaryProfiler.Cost cost = profiler.measure(() -> {
				try {
					return action.apply(proxy);
				} catch (RuntimeException e) {
					return e;
				}
			});
			if (run >= 0) { // run -1 warms the JVM and the database's caches
				millis[run] = cost.nanos() / 1e6;
				last = cost;
			}
		}
		Arrays.sort(millis);
		return new Row(name, last, millis);
	}

	private static Object validate(Object proxy) {
		PlsqlPackages.validate(proxy);
		return null;
	}

	@Test
	void report() throws Exception {
		assumeTrue(Boolean.getBoolean("penitent.describe.report"), "only when asked: -Dpenitent.describe.report=true");
		List<Row> rows = new ArrayList<>();
		try (HikariDataSource pool = newPool();
				DictionaryProfiler profiler = new DictionaryProfiler(pool, adminConnection())) {
			rows.add(scenario(profiler, "first call, scalar", ScalarsTest.Scalars.class,
					s -> s.plus(BigDecimal.ONE, BigDecimal.ONE)));
			rows.add(scenario(profiler, "first call, OBJECT", ObjectTypesTest.Composites.class,
					s -> s.echoAddress(new ObjectTypesTest.Address("A", 1))));
			rows.add(scenario(profiler, "first call, collection of OBJECT", CollectionsTest.Composites.class,
					s -> s.echoAddresses(List.of(new CollectionsTest.Address("A", 1)))));
			rows.add(scenario(profiler, "first call, nested OBJECT", ObjectTypesTest.Composites.class,
					s -> s.echoCustomer(new ObjectTypesTest.Customer(List.of(new ObjectTypesTest.Address("B", 2)), 7L,
							"Ann Doe", true, true, LocalDate.parse("1990-05-20"), ObjectTypesTest.Status.NEW,
							new ObjectTypesTest.Address("A", 1), new String[] { "Annie" }))));
			rows.add(scenario(profiler, "validate, 24 methods", ScalarsTest.Scalars.class, DescribeTimingReport::validate));
			rows.add(scenario(profiler, "validate, 33 methods", OutParametersTest.Outputs.class,
					DescribeTimingReport::validate));
			rows.add(scenario(profiler, "validate, 36 methods, overloads", OverloadingTest.Overload.class,
					DescribeTimingReport::validate));
			rows.add(scenario(profiler, "validate, 38 methods", TimeZoneTest.TimeZones.class,
					DescribeTimingReport::validate));
			rows.add(scenario(profiler, "validate, 30 standalone, 10 through synonyms",
					StandaloneResolutionTest.Legacy.class, DescribeTimingReport::validate));
		}
		StringBuilder table = new StringBuilder(String.format(Locale.ROOT,
				"Oracle %d, driver %d, %d runs each%n%n", DATABASE_MAJOR_VERSION,
				new oracle.jdbc.OracleDriver().getMajorVersion(), RUNS));
		table.append("| Scenario | Queries | Round trips | p50 | p90 | p95 | p99 | p50 at 1 ms | p50 at 5 ms |\n"
				+ "|---|---|---|---|---|---|---|---|---|\n");
		for (Row row : rows) {
			long trips = row.cost().roundTrips();
			double p50 = percentile(row.millis(), 50);
			table.append(String.format(Locale.ROOT, "| %s | %d | %s | %.1f ms | %.1f ms | %.1f ms | %.1f ms | %s | %s |%n",
					row.scenario(), row.cost().count(), trips < 0 ? "n/a" : String.valueOf(trips), p50,
					percentile(row.millis(), 90), percentile(row.millis(), 95), percentile(row.millis(), 99),
					modeled(p50, trips, 1), modeled(p50, trips, 5)));
		}
		System.out.println(table);
		writeReport(table.toString());
	}

	/** The median as a database {@code latency} ms away would give it, one latency per round trip. */
	private static String modeled(double p50, long roundTrips, int latency) {
		return roundTrips < 0 ? "n/a" : String.format(Locale.ROOT, "~%.0f ms", p50 + roundTrips * latency);
	}

	private static void writeReport(String table) throws IOException {
		Path target = Path.of("target", "describe-timing.md");
		Files.createDirectories(target.getParent());
		Files.writeString(target, table, StandardCharsets.UTF_8);
	}
}
