package io.github.rponte.penitent;

import java.util.Objects;

import javax.sql.DataSource;

import io.github.rponte.penitent.internal.transaction.LocalTransactions;

/**
 * Runs a block in one transaction, for applications that use the core alone, without Spring or a
 * Jakarta EE container:
 *
 * <pre>{@code
 * PlsqlTransactions transactions = PlsqlTransactions.of(dataSource);   // cheap: once, or where you need it
 *
 * transactions.run(tx -> {
 *     Orders orders = tx.using(ordersPkg);
 *     orders.placeOrder(42, total, orderId);
 *     legacyAudit(tx.connection(), orderId.get());   // plain JDBC, in the same transaction
 * });                                                // committed if it returns, rolled back if it throws
 * }</pre>
 *
 * <p>The transaction is explicit and strict. An interface takes part only through
 * {@link PlsqlTransaction#using}; one of the same {@code DataSource} called directly inside the block
 * is refused, instead of running outside the transaction without anyone noticing. "The same" means the
 * same object: an interface created with a wrapper of the {@code DataSource}, or with another pool of
 * the same database, is not recognized, so create both from the same {@code DataSource} object.
 *
 * <p>An instance holds only the {@code DataSource} and the exception translator: it is cheap, immutable
 * and safe to share, and creating one where it is needed costs nothing.
 *
 * <p>The block commits when it returns, unless {@link PlsqlTransaction#setRollbackOnly} was called,
 * and rolls back when it throws anything, an {@link Error} or a checked exception included, which
 * comes out as it was thrown. The connection always goes back to the {@code DataSource} with its
 * auto-commit as it found it.
 *
 * <p>It is deliberately small: one connection, one thread, no nesting, no propagation. For more,
 * use Spring or a Jakarta EE container, which the interfaces already take part in.
 */
public interface PlsqlTransactions {

	/** Transactions on connections of {@code dataSource}. A failed commit arrives as a {@code PlsqlExecutionException}. */
	static PlsqlTransactions of(DataSource dataSource) {
		return of(dataSource, PlsqlSettings.defaults());
	}

	/** The same, with the settings' exception translator for a failed commit or a connection that cannot be obtained. */
	static PlsqlTransactions of(DataSource dataSource, PlsqlSettings settings) {
		Objects.requireNonNull(dataSource, "dataSource");
		Objects.requireNonNull(settings, "settings");
		return new LocalTransactions(dataSource, settings);
	}

	/**
	 * Runs {@code work} in a new transaction, and commits it when {@code work} returns.
	 *
	 * @throws E what {@code work} throws, as it was thrown, after the rollback
	 * @throws IllegalStateException if this thread is already inside a block
	 */
	<E extends Exception> void run(Work<E> work) throws E;

	/**
	 * The same as {@link #run}, returning what {@code work} returns, after the commit.
	 *
	 * @throws E what {@code work} throws, as it was thrown, after the rollback
	 * @throws IllegalStateException if this thread is already inside a block
	 */
	<T, E extends Exception> T runWithResult(WorkWithResult<T, E> work) throws E;

	/** A block without a result. {@code E} lets it throw a checked exception, which comes out as it is. */
	@FunctionalInterface
	interface Work<E extends Exception> {

		void run(PlsqlTransaction tx) throws E;
	}

	/** A block with a result. */
	@FunctionalInterface
	interface WorkWithResult<T, E extends Exception> {

		T run(PlsqlTransaction tx) throws E;
	}
}
