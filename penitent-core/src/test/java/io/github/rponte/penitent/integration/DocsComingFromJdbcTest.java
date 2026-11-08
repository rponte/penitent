package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;

/**
 * The examples of docs/coming-from-jdbc.md: the JDBC call and the Penitent one, side by side. The
 * {@code SimpleJdbcCall} version needs Spring, which this module does not have, so the page marks
 * it as untested.
 */
class DocsComingFromJdbcTest extends OracleIntegrationTest {

	// snippet: jdbc/penitent-interface
	@PlsqlPackage("ORDERS_PKG")
	public interface Orders {
	    void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);
	}
	// end-snippet

	@PlsqlPackage("ORDERS_PKG")
	interface Statuses {
		String orderStatus(long orderId);

		long countOrders(long customerId);
	}

	private final DataSource dataSource = DATA_SOURCE;

	private final Statuses statuses = PlsqlPackages.create(Statuses.class, DATA_SOURCE);

	@Test
	@DisplayName("the same call with JDBC and with Penitent places an order")
	void the_same_call() throws SQLException {
		long before = statuses.countOrders(42);

		// snippet: jdbc/callable-statement
		try (Connection connection = dataSource.getConnection();
		     CallableStatement call = connection.prepareCall("{call ORDERS_PKG.PLACE_ORDER(?, ?, ?)}")) {
		    call.setLong(1, 42);
		    call.setBigDecimal(2, new BigDecimal("100.00"));
		    call.registerOutParameter(3, Types.NUMERIC);
		    call.execute();
		    long orderId = call.getLong(3);
		}
		// end-snippet

		assertThat(statuses.countOrders(42)).isEqualTo(before + 1);

		Orders orders = PlsqlPackages.create(Orders.class, dataSource);
		// snippet: jdbc/penitent-call
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		orders.placeOrder(42, new BigDecimal("100.00"), orderId);
		long id = orderId.get();
		// end-snippet

		assertThat(statuses.orderStatus(id)).isEqualTo("OPEN");
	}
}
