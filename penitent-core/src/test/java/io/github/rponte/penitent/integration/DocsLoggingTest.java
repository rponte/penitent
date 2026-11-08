package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * The log of docs/logging.md, as the page shows it. Durations change from run to run, so they are
 * compared as {@code # ms}; everything else is compared as it is.
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
		return normalized(Docs.asDocumented(log.toString(), DocsLoggingTest.class));
	}

	private static String normalized(String log) {
		return log.replaceAll("\\d+ ms", "# ms").replaceAll("line \\d+", "line #");
	}

	@Test
	@DisplayName("a composite in and out, on the first call")
	void composite() {
		PlsqlPackages.create(Customers.class, DATA_SOURCE)
				.normalizeAddress(new Address("rua das flores 10", "Fortaleza", "60000-000"));

		assertThat(log()).isEqualTo(normalized("""
				DEBUG Read dictionary     : SHOP.CUSTOMERS_PKG.NORMALIZE_ADDRESS in 38 ms
				DEBUG Calling routine     : SHOP.CUSTOMERS_PKG.NORMALIZE_ADDRESS <- Customers.normalizeAddress
				DEBUG -> SQL              : {? = call SHOP.CUSTOMERS_PKG.NORMALIZE_ADDRESS(?)}
				TRACE -> with bind values : P_ADDRESS=SHOP.POSTAL_ADDRESS_T('rua das flores 10', 'Fortaleza', '60000-000')
				TRACE <- result           : SHOP.POSTAL_ADDRESS_T('Rua Das Flores 10', 'FORTALEZA', '60000000')
				DEBUG <- completed        : 4 ms
				"""));
	}

	@Test
	@DisplayName("by name, with three parameters, and a list as the result")
	void by_name_and_list() {
		PlsqlPackages.create(OrderSearch.class, DATA_SOURCE).countOrders(501, "OPEN", new BigDecimal("100"));
		PlsqlPackages.create(Customers.class, DATA_SOURCE).tagsOf(1);

		assertThat(log()).isEqualTo(normalized("""
				DEBUG Read dictionary     : SHOP.ORDERS_PKG.COUNT_ORDERS in 35 ms
				DEBUG Calling routine     : SHOP.ORDERS_PKG.COUNT_ORDERS <- OrderSearch.countOrders
				DEBUG -> SQL              : {? = call SHOP.ORDERS_PKG.COUNT_ORDERS(
				DEBUG                         P_CUSTOMER_ID => ?,
				DEBUG                         P_STATUS      => ?,
				DEBUG                         P_MIN_TOTAL   => ?
				DEBUG                       )}
				TRACE -> with bind values : 3 parameters
				TRACE                         P_CUSTOMER_ID = 501,
				TRACE                         P_STATUS      = 'OPEN',
				TRACE                         P_MIN_TOTAL   = 100
				TRACE <- result           : 0
				DEBUG <- completed        : 3 ms
				DEBUG Read dictionary     : SHOP.CUSTOMERS_PKG.TAGS_OF in 22 ms
				DEBUG Calling routine     : SHOP.CUSTOMERS_PKG.TAGS_OF <- Customers.tagsOf
				DEBUG -> SQL              : {? = call SHOP.CUSTOMERS_PKG.TAGS_OF(?)}
				TRACE -> with bind values : P_ID=1
				TRACE <- result           : [
				TRACE                         'vip',
				TRACE                         'newsletter'
				TRACE                       ]
				DEBUG <- completed        : 2 ms
				"""));
	}

	@Test
	@DisplayName("IN OUT, and a failure")
	void out_and_failure() {
		Orders orders = PlsqlPackages.create(Orders.class, DATA_SOURCE);
		orders.applyDiscount(PlsqlOut.of(new BigDecimal("200")));
		assertThatThrownBy(() -> orders.orderStatus(-1)).isInstanceOf(PlsqlRoutineRaisedException.class);

		assertThat(log()).isEqualTo(normalized("""
				DEBUG Read dictionary     : SHOP.ORDERS_PKG.APPLY_DISCOUNT in 31 ms
				DEBUG Calling routine     : SHOP.ORDERS_PKG.APPLY_DISCOUNT <- Orders.applyDiscount
				DEBUG -> SQL              : {call SHOP.ORDERS_PKG.APPLY_DISCOUNT(?)}
				TRACE -> with bind values : P_TOTAL=200
				TRACE <- OUT values       : P_TOTAL=180
				DEBUG <- completed        : 2 ms
				DEBUG Read dictionary     : SHOP.ORDERS_PKG.ORDER_STATUS in 9 ms
				DEBUG Calling routine     : SHOP.ORDERS_PKG.ORDER_STATUS <- Orders.orderStatus
				DEBUG -> SQL              : {? = call SHOP.ORDERS_PKG.ORDER_STATUS(?)}
				TRACE -> with bind values : P_ORDER_ID=-1
				DEBUG <- failed           : 1 ms: SQLException: ORA-01403: no data found | ORA-06512: at "SHOP.ORDERS_PKG", line 26 | ORA-06512: at line 1 | https://docs.oracle.com/error-help/db/ora-01403/
				"""));
	}
}
