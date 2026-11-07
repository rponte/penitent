package io.github.rponte.penitent.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

/**
 * A {@link DataSource} that records the statements prepared on its connections, to count how many of
 * them read the data dictionary.
 */
final class QueryCounter {

	private final List<String> statements = new CopyOnWriteArrayList<>();
	private final DataSource dataSource;

	QueryCounter(DataSource target) {
		this.dataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					Object result = invoke(target, method, args);
					return result instanceof Connection connection ? recording(connection) : result;
				});
	}

	DataSource dataSource() {
		return dataSource;
	}

	/** The statements that read the dictionary since the last {@link #reset()}. */
	long dictionaryQueries() {
		return statements.stream().filter(sql -> sql.matches("(?is).*\\b(all_\\w+|sys_context)\\b.*")).count();
	}

	void reset() {
		statements.clear();
	}

	private Connection recording(Connection connection) {
		return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
				(proxy, method, args) -> {
					if (method.getName().startsWith("prepare") && args != null && args[0] instanceof String sql) {
						statements.add(sql);
					}
					return invoke(connection, method, args);
				});
	}

	private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}
}
