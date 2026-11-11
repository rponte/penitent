package io.github.rponte.penitent.boot;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/**
 * A database that is down, and counts how many times a connection was asked for: one per call that
 * reached the database, since a call takes its connection before anything else.
 */
final class CountingDataSource {

	private final AtomicInteger connectionsAsked = new AtomicInteger();

	int connectionsAsked() {
		return connectionsAsked.get();
	}

	DataSource dataSource() {
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> switch (method.getName()) {
					case "getConnection" -> {
						connectionsAsked.incrementAndGet();
						throw new SQLException("database is down");
					}
					case "toString" -> "counting DataSource";
					case "hashCode" -> System.identityHashCode(proxy);
					case "equals" -> proxy == args[0];
					default -> throw new UnsupportedOperationException(method.getName());
				});
	}
}
