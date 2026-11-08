package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/**
 * The examples of docs/transactions.md. The regions are what the page shows, and
 * {@link DocsPagesTest} checks that the page still shows them. {@link TransactionsTest} covers the
 * same behaviors in more detail.
 */
class DocsTransactionsTest extends OracleIntegrationTest {

	@PlsqlPackage("ORDERS_PKG")
	interface Orders {
		void placeOrder(long customerId, BigDecimal total, PlsqlOut<Long> orderId);

		void cancelOrder(long orderId);
	}

	@PlsqlPackage("ORDERS_PKG")
	interface Counting {
		long countOrders(long customerId, String status, BigDecimal minTotal);
	}

	private final DataSource dataSource = DATA_SOURCE;

	/** What another session sees: only what was committed. */
	private final Counting committed = PlsqlPackages.create(Counting.class, DATA_SOURCE);

	@Test
	@DisplayName("with a pool in auto-commit, each call commits on its own")
	void auto_commit() {
		Orders orders = PlsqlPackages.create(Orders.class, dataSource);

		assertThatThrownBy(() -> {
			// snippet: transactions/auto-commit
			PlsqlOut<Long> orderId = new PlsqlOut<>();
			orders.placeOrder(601, new BigDecimal("100.00"), orderId);   // committed when the call ends
			orders.cancelOrder(-1);                                      // fails, and the order above stays
			// end-snippet
		}).isInstanceOf(PlsqlRoutineRaisedException.class);

		assertThat(committed.countOrders(601, null, null)).isEqualTo(1);
	}

	@Test
	@DisplayName("in a transaction of the application's, calls and JDBC are committed together")
	void transaction() throws SQLException {
		// snippet: transactions/create
		Orders orders = PlsqlPackages.create(Orders.class, dataSource);   // once, as usual
		// end-snippet

		// snippet: transactions/transaction
		try (Connection connection = dataSource.getConnection()) {
		    connection.setAutoCommit(false);
		    try {
		        Orders inTransaction = PlsqlPackages.using(orders, connection);

		        PlsqlOut<Long> orderId = new PlsqlOut<>();
		        inTransaction.placeOrder(602, new BigDecimal("100.00"), orderId);
		        try (PreparedStatement discount = connection.prepareStatement(
		                "update orders set total = total * 0.9 where id = ?")) {
		            discount.setLong(1, orderId.get());
		            discount.executeUpdate();
		        }

		        connection.commit();
		    } catch (RuntimeException | SQLException e) {
		        connection.rollback();
		        throw e;
		    } finally {
		        connection.setAutoCommit(true);
		    }
		}
		// end-snippet

		assertThat(committed.countOrders(602, "OPEN", new BigDecimal("90"))).as("placed and discounted").isEqualTo(1);
		assertThat(committed.countOrders(602, "OPEN", new BigDecimal("90.01"))).isZero();
	}

	@Test
	@DisplayName("in a transaction of the application's, a failure rolls back the calls and the JDBC before it")
	void transaction_rolled_back() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try {
				Orders orders = PlsqlPackages.using(PlsqlPackages.create(Orders.class, dataSource), connection);
				PlsqlOut<Long> orderId = new PlsqlOut<>();
				orders.placeOrder(603, new BigDecimal("100.00"), orderId);
				orders.cancelOrder(-1);
				connection.commit();
			} catch (RuntimeException | SQLException e) {
				connection.rollback();
			} finally {
				connection.setAutoCommit(true);
			}
		}

		assertThat(committed.countOrders(603, null, null)).isZero();
	}

	@Test
	@DisplayName("a failed call undoes only its own work, and the application decides about the rest")
	void failure_in_the_middle() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try {
				Orders inTransaction = PlsqlPackages.using(PlsqlPackages.create(Orders.class, dataSource), connection);
				PlsqlOut<Long> orderId = new PlsqlOut<>();

				// snippet: transactions/failure-in-the-middle
				inTransaction.placeOrder(604, new BigDecimal("100.00"), orderId);
				try {
				    inTransaction.cancelOrder(-1);
				} catch (PlsqlRoutineRaisedException e) {
				    // only cancel_order was undone: the order placed above is still in the transaction
				}
				connection.commit();   // keeps the order
				// end-snippet
			} finally {
				connection.rollback();
				connection.setAutoCommit(true);
			}
		}

		assertThat(committed.countOrders(604, null, null)).isEqualTo(1);
	}
}
