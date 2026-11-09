package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlParam;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;
import oracle.jdbc.OracleDriver;

/**
 * The log of docs/logging.md, compared with the page. Durations and line numbers change from run to
 * run, so they are compared as {@code #}; everything else is compared as it is.
 */
class DocsLoggingTest extends OracleIntegrationTest {

	private static final List<String> LOGGERS = List.of("io.github.rponte.penitent.call", "io.github.rponte.penitent.bind");

	record Address(String street, String city, String zipCode) {
	}

	@PlsqlPackage("CUSTOMERS_PKG")
	interface Customers {
		Address normalizeAddress(Address address);

		List<String> tagsOf(long id);
	}

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		void applyDiscount(PlsqlOut<BigDecimal> total);

		String orderStatus(long orderId);
	}

	@PlsqlPackage(value = "ORDERS_PKG", bind = PlsqlBind.BY_NAME)
	interface OrderSearch {
		long countOrders(@PlsqlParam("P_CUSTOMER_ID") long customerId, @PlsqlParam("P_STATUS") String status,
				@PlsqlParam("P_MIN_TOTAL") BigDecimal minTotal);
	}

	private final ListAppender<ILoggingEvent> events = new ListAppender<>();

	@BeforeEach
	void capture() {
		events.start();
		for (String name : LOGGERS) {
			logger(name).addAppender(events);
			logger(name).setAdditive(false);
		}
	}

	@AfterEach
	void restore() {
		for (String name : LOGGERS) {
			logger(name).detachAppender(events);
			logger(name).setAdditive(true);
		}
	}

	private static Logger logger(String name) {
		return (Logger) LoggerFactory.getLogger(name);
	}

	private String log() {
		StringBuilder log = new StringBuilder();
		for (ILoggingEvent event : events.list) {
			log.append(String.format("%-5s %s%n", event.getLevel(), event.getFormattedMessage()));
		}
		return normalized(Docs.asDocumented(log.toString(), DocsLoggingTest.class)).stripTrailing();
	}

	private static final int DRIVER = new OracleDriver().getMajorVersion();

	/**
	 * What changes between runs, and, before driver 23, the link to the error's page that driver 23
	 * appends to the database's message: the page shows the default driver's output, and the rest of
	 * the log is the same on every driver.
	 */
	private static String normalized(String log) {
		String normalized = log.replaceAll("\\d+ ms", "# ms").replaceAll("line \\d+", "line #");
		return DRIVER >= 23 ? normalized : normalized.replaceAll(" \\| https://docs\\.oracle\\.com/error-help/\\S+", "");
	}

	@Test
	@DisplayName("a composite in and out, on the first call")
	void composite() {
		PlsqlPackages.create(Customers.class, DATA_SOURCE)
				.normalizeAddress(new Address("rua das flores 10", "Fortaleza", "60000-000"));

		Docs.assertPagesShow(DocsLoggingTest::normalized, log(), "logging.md#composite");
	}

	@Test
	@DisplayName("by name, with three parameters, and a list as the result")
	void by_name_and_list() {
		PlsqlPackages.create(OrderSearch.class, DATA_SOURCE).countOrders(501, "OPEN", new BigDecimal("100"));
		PlsqlPackages.create(Customers.class, DATA_SOURCE).tagsOf(1);

		Docs.assertPagesShow(DocsLoggingTest::normalized, log(), "logging.md#by-name-and-list");
	}

	@Test
	@DisplayName("IN OUT, and a failure")
	void out_and_failure() {
		Orders orders = PlsqlPackages.create(Orders.class, DATA_SOURCE);
		orders.applyDiscount(PlsqlOut.of(new BigDecimal("200")));
		assertThatThrownBy(() -> orders.orderStatus(-1)).isInstanceOf(PlsqlRoutineRaisedException.class);

		Docs.assertPagesShow(DocsLoggingTest::normalized, log(), "logging.md#out-and-failure");
	}
}
