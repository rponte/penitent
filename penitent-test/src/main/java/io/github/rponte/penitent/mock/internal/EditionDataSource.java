package io.github.rponte.penitent.mock.internal;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * The application's {@code DataSource} in tests: while a test runs, each connection it hands out enters the test's
 * edition, and goes back to the database's default edition when it is closed, so the pool never keeps a session in
 * an edition that is about to be dropped. With no test running, it hands out the connections as they are.
 */
public final class EditionDataSource implements DataSource {

	private final DataSource target;
	private volatile String edition;
	private volatile String defaultEdition;

	public EditionDataSource(DataSource target) {
		this.target = target;
	}

	/** The {@code DataSource} wrapped: its connections are always in the default edition. */
	public DataSource target() {
		return target;
	}

	/** The edition connections enter from now on, or {@code null} for none. */
	public void enter(String edition, String defaultEdition) {
		this.defaultEdition = defaultEdition;
		this.edition = edition;
	}

	@Override
	public Connection getConnection() throws SQLException {
		return inEdition(target.getConnection());
	}

	@Override
	public Connection getConnection(String username, String password) throws SQLException {
		return inEdition(target.getConnection(username, password));
	}

	private Connection inEdition(Connection connection) throws SQLException {
		String current = edition;
		if (current == null) {
			return connection;
		}
		String back = defaultEdition;
		try {
			execute(connection, "ALTER SESSION SET EDITION = " + current);
		} catch (SQLException e) {
			connection.close();
			throw e;
		}
		return (Connection) Proxy.newProxyInstance(EditionDataSource.class.getClassLoader(),
				new Class<?>[] { Connection.class }, (proxy, method, args) -> {
					if (method.getName().equals("close") && !connection.isClosed()) {
						// ALTER SESSION SET EDITION has to start a transaction: whatever is pending ends first
						if (!connection.getAutoCommit()) {
							connection.rollback();
						}
						execute(connection, "ALTER SESSION SET EDITION = " + back);
					}
					try {
						return method.invoke(connection, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
	}

	static void execute(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	@Override
	public PrintWriter getLogWriter() throws SQLException {
		return target.getLogWriter();
	}

	@Override
	public void setLogWriter(PrintWriter out) throws SQLException {
		target.setLogWriter(out);
	}

	@Override
	public void setLoginTimeout(int seconds) throws SQLException {
		target.setLoginTimeout(seconds);
	}

	@Override
	public int getLoginTimeout() throws SQLException {
		return target.getLoginTimeout();
	}

	@Override
	public Logger getParentLogger() throws SQLFeatureNotSupportedException {
		return target.getParentLogger();
	}

	@Override
	public <T> T unwrap(Class<T> type) throws SQLException {
		return type.isInstance(this) ? type.cast(this) : target.unwrap(type);
	}

	@Override
	public boolean isWrapperFor(Class<?> type) throws SQLException {
		return type.isInstance(this) || target.isWrapperFor(type);
	}
}
