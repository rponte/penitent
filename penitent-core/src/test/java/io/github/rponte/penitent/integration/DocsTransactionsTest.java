package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlTransaction;
import io.github.rponte.penitent.PlsqlTransactions;
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
	@DisplayName("PlsqlTransactions: the block commits the calls and the JDBC work together")
	void plsql_transactions() throws SQLException {
		Orders orders = PlsqlPackages.create(Orders.class, dataSource);

		// snippet: transactions/plsql-transactions
		PlsqlTransactions transactions = PlsqlTransactions.of(dataSource);   // once, like the interfaces
		// end-snippet

		// snippet: transactions/run
		transactions.run(tx -> {
		    Orders inTransaction = tx.using(orders);

		    PlsqlOut<Long> orderId = new PlsqlOut<>();
		    inTransaction.placeOrder(605, new BigDecimal("100.00"), orderId);
		    try (PreparedStatement discount = tx.connection().prepareStatement(
		            "update orders set total = total * 0.9 where id = ?")) {
		        discount.setLong(1, orderId.get());
		        discount.executeUpdate();
		    }
		});   // committed: the block returned
		// end-snippet

		assertThat(committed.countOrders(605, "OPEN", new BigDecimal("90"))).isEqualTo(1);
		assertThat(committed.countOrders(605, "OPEN", new BigDecimal("90.01"))).isZero();

		// snippet: transactions/run-with-result
		long orderId = transactions.runWithResult(tx -> {
		    PlsqlOut<Long> id = new PlsqlOut<>();
		    tx.using(orders).placeOrder(606, new BigDecimal("50.00"), id);
		    return id.get();
		});
		// end-snippet

		assertThat(orderId).isPositive();
		assertThat(committed.countOrders(606, null, null)).isEqualTo(1);
	}

	private final Orders orders = PlsqlPackages.create(Orders.class, DATA_SOURCE);

	private final PlsqlTransactions transactions = PlsqlTransactions.of(DATA_SOURCE);

	@Test
	@DisplayName("exceptions: a block without checked exceptions asks nothing of its caller")
	void exceptions_none() {
		PlsqlOut<Long> orderId = new PlsqlOut<>();

		// snippet: transactions/exceptions-none
		transactions.run(tx -> tx.using(orders).placeOrder(607, new BigDecimal("10.00"), orderId));   // no try, no throws
		// end-snippet

		assertThat(committed.countOrders(607, null, null)).isEqualTo(1);
	}

	@Test
	@DisplayName("exceptions: a SQLException of the block reaches the caller as it is")
	void exceptions_sql() throws SQLException {
		PlsqlOut<Long> orderId = new PlsqlOut<>();
		transactions.run(tx -> tx.using(orders).placeOrder(608, new BigDecimal("100.00"), orderId));

		discount(orderId.get());

		assertThat(committed.countOrders(608, null, new BigDecimal("90"))).isEqualTo(1);
		assertThat(committed.countOrders(608, null, new BigDecimal("90.01"))).isZero();
	}

	// snippet: transactions/exceptions-sql
	void discount(long orderId) throws SQLException {   // the same as without the block
	    transactions.run(tx -> {
	        try (PreparedStatement update = tx.connection().prepareStatement(
	                "update orders set total = total * 0.9 where id = ?")) {
	            update.setLong(1, orderId);
	            update.executeUpdate();
	        }
	    });
	}
	// end-snippet

	@Test
	@DisplayName("exceptions: with two checked types, E is Exception, and the original still arrives unwrapped")
	void exceptions_two_types() {

		// snippet: transactions/exceptions-two-types
		try {
		    transactions.run(tx -> {
		        PlsqlOut<Long> orderId = new PlsqlOut<>();
		        tx.using(orders).placeOrder(609, new BigDecimal("100.00"), orderId);
		        discount(tx, orderId.get());   // throws SQLException
		        export(orderId.get());         // throws IOException
		    });
		} catch (IOException e) {
		    // the original IOException, not a wrapper; the order was rolled back
		    retryExportLater(e);
		} catch (Exception e) {
		    // E was inferred as Exception, the common supertype, so the compiler asks for it
		    reportFailure(e);
		}
		// end-snippet

		assertThat(caught).containsExactly("retryExportLater: IOException");
		assertThat(committed.countOrders(609, null, null)).as("rolled back").isZero();
	}

	private final List<String> caught = new java.util.ArrayList<>();

	private void retryExportLater(IOException e) {
		caught.add("retryExportLater: " + e.getClass().getSimpleName());
	}

	private void reportFailure(Exception e) {
		caught.add("reportFailure: " + e.getClass().getSimpleName());
	}

	private static void discount(PlsqlTransaction tx, long orderId) throws SQLException {
		try (PreparedStatement update = tx.connection().prepareStatement("update orders set total = total * 0.9 where id = ?")) {
			update.setLong(1, orderId);
			update.executeUpdate();
		}
	}

	private static void export(long orderId) throws IOException {
		throw new IOException("export of order " + orderId + " failed");
	}

	@Test
	@DisplayName("PlsqlTransactions: a proxy of the same DataSource called without tx.using is refused")
	void plsql_transactions_refuses() {
		Orders orders = PlsqlPackages.create(Orders.class, dataSource);

		assertThatThrownBy(() -> PlsqlTransactions.of(dataSource).run(tx -> orders.cancelOrder(-1)))
				.isInstanceOf(IllegalStateException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), DocsTransactionsTest.class),
						"transactions.md#outside-the-transaction"));
	}

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
