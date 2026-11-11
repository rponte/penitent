package io.github.rponte.penitent.internal.proxy;

import javax.sql.DataSource;

import io.github.rponte.penitent.internal.transaction.TransactionIntegration;

/**
 * The {@code DataSource} of the {@code PlsqlTransaction} open on the current thread, if any. It only
 * serves to refuse: a nested block, and a proxy of that {@code DataSource} called outside the
 * transaction. It never changes where a call runs.
 *
 * <p>The {@code DataSource} is compared by identity, after {@link TransactionIntegration#targetOf}: a
 * wrapper that only makes connections take part in another model's transactions, such as the one an
 * interface of {@code penitent-spring} uses, is the {@code DataSource} it wraps.
 */
public final class TransactionScope {

	private static final ThreadLocal<DataSource> OPEN = new ThreadLocal<>();

	private TransactionScope() {
	}

	public static boolean isOpen() {
		return OPEN.get() != null;
	}

	static boolean isOpenOn(DataSource dataSource) {
		return OPEN.get() == TransactionIntegration.targetOf(dataSource);
	}

	public static void open(DataSource dataSource) {
		OPEN.set(TransactionIntegration.targetOf(dataSource));
	}

	public static void close() {
		OPEN.remove();
	}
}
