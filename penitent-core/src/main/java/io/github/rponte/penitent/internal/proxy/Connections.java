package io.github.rponte.penitent.internal.proxy;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

/**
 * Where a proxy gets the connection of each call, and whether it gives it back. The library never
 * commits, rolls back or changes auto-commit: the transaction belongs to whoever owns the connection.
 *
 * <p>Internal for now, in the shape of a public provider: if one is ever needed, for instance to
 * prepare the session before each call, it can open up without changing the proxy.
 */
sealed interface Connections {

	/** A connection for one call, or for one validation; closing the lease gives it back. */
	Lease acquire() throws SQLException;

	/** {@code owned}: the library took it from a {@code DataSource}, so the library closes it. */
	record Lease(Connection connection, boolean owned) implements AutoCloseable {

		@Override
		public void close() throws SQLException {
			if (owned) {
				connection.close();
			}
		}
	}

	/** What {@code PlsqlPackages.create} uses: a connection from the {@code DataSource} per call, closed at its end. */
	record FromDataSource(DataSource dataSource) implements Connections {

		@Override
		public Lease acquire() throws SQLException {
			return new Lease(dataSource.getConnection(), true);
		}
	}

	/**
	 * What {@code PlsqlPackages.using} and a {@code PlsqlTransaction} use: always the application's
	 * connection, which stays open. {@code guard} runs before each call, and refuses it by throwing,
	 * as a transaction does once its block ended.
	 */
	record Bound(Connection connection, Runnable guard) implements Connections {

		@Override
		public Lease acquire() {
			guard.run();
			return new Lease(connection, false);
		}
	}
}
