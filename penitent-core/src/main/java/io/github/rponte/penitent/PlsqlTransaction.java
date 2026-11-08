package io.github.rponte.penitent;

import java.sql.Connection;

/**
 * The transaction of a block run by {@link PlsqlTransactions}. It is valid only inside the block,
 * and only on the thread that runs it: afterwards, or from another thread, what it handed out fails.
 */
public interface PlsqlTransaction {

	/**
	 * A view of {@code proxy} that runs its calls in this transaction, as
	 * {@link PlsqlPackages#using} does, sharing the proxy's cache and settings.
	 *
	 * @throws IllegalArgumentException if {@code proxy} was not created by this library
	 */
	<T> T using(T proxy);

	/**
	 * The transaction's connection, for plain JDBC in the same transaction. It refuses what would
	 * end the transaction behind the block's back: {@code commit}, {@code rollback()},
	 * {@code setAutoCommit}, {@code close} and {@code abort} throw a {@code SQLException}. Savepoints
	 * work, and {@code unwrap} reaches the driver's connection, which has none of these protections.
	 */
	Connection connection();

	/** Rolls the transaction back when the block returns, instead of committing it. */
	void setRollbackOnly();

	boolean isRollbackOnly();
}
