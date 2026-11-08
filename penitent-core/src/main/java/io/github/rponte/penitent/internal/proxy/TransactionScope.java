package io.github.rponte.penitent.internal.proxy;

import javax.sql.DataSource;

/**
 * The {@code DataSource} of the {@code PlsqlTransaction} open on the current thread, if any. It only
 * serves to refuse: a nested block, and a proxy of that {@code DataSource} called outside the
 * transaction. It never changes where a call runs.
 */
public final class TransactionScope {

	private static final ThreadLocal<DataSource> OPEN = new ThreadLocal<>();

	private TransactionScope() {
	}

	public static boolean isOpen() {
		return OPEN.get() != null;
	}

	static boolean isOpenOn(DataSource dataSource) {
		return OPEN.get() == dataSource;
	}

	public static void open(DataSource dataSource) {
		OPEN.set(dataSource);
	}

	public static void close() {
		OPEN.remove();
	}
}
