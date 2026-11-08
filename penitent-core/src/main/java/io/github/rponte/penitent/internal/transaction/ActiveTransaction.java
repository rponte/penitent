package io.github.rponte.penitent.internal.transaction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Set;

import io.github.rponte.penitent.PlsqlTransaction;
import io.github.rponte.penitent.internal.proxy.Views;

/**
 * The {@code tx} of one block. What it hands out, views and the connection, works only while the
 * block runs, and only on the thread that runs it.
 */
final class ActiveTransaction implements PlsqlTransaction {

	/** What would end the transaction behind the block's back. {@code rollback(Savepoint)} does not. */
	private static final Set<String> FORBIDDEN = Set.of("commit", "setAutoCommit", "close", "abort");

	private final Connection connection;
	private final Thread owner;
	private final Connection protectedConnection;
	private volatile boolean active = true;
	private volatile boolean rollbackOnly;

	ActiveTransaction(Connection connection, Thread owner) {
		this.connection = connection;
		this.owner = owner;
		this.protectedConnection = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					String problem = problem();
					if (problem != null) {
						throw new SQLException(problem);
					}
					String name = method.getName();
					if (FORBIDDEN.contains(name) || name.equals("rollback") && (args == null || args.length == 0)) {
						throw new SQLException(name + " is not allowed on the connection of a PlsqlTransaction: the block"
								+ " commits when it returns, and rolls back when it throws or calls tx.setRollbackOnly()");
					}
					try {
						return method.invoke(connection, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	@Override
	public <T> T using(T proxy) {
		Objects.requireNonNull(proxy, "proxy");
		return Views.bind(proxy, connection, () -> {
			String problem = problem();
			if (problem != null) {
				throw new IllegalStateException(problem);
			}
		});
	}

	@Override
	public Connection connection() {
		return protectedConnection;
	}

	@Override
	public void setRollbackOnly() {
		rollbackOnly = true;
	}

	@Override
	public boolean isRollbackOnly() {
		return rollbackOnly;
	}

	void end() {
		active = false;
	}

	/** Why what this transaction handed out cannot be used now, or null. */
	private String problem() {
		if (!active) {
			return "the PlsqlTransaction ended with its block: use what it hands out only inside the block";
		}
		if (Thread.currentThread() != owner) {
			return "the PlsqlTransaction belongs to thread " + owner.getName() + ", and was used from thread "
					+ Thread.currentThread().getName() + ": a connection serves one thread at a time";
		}
		return null;
	}
}
