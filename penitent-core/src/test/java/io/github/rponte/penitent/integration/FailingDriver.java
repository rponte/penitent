package io.github.rponte.penitent.integration;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Struct;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/**
 * A driver that fails on demand: a {@code DataSource} over a real one, whose objects pass every call to the real
 * driver, but throw the given failure once, at the first call of a method on an object of a type, as the Oracle driver
 * throws a runtime exception of its own. Everything else runs in the database. Each {@code abort()} of a connection is
 * counted, and passed on, so the pool replaces the connection.
 */
final class FailingDriver {

	private static final List<Class<?>> WRAPPED = List.of(CallableStatement.class, PreparedStatement.class,
			Statement.class, ResultSet.class, Struct.class, Array.class, NClob.class, Clob.class, Blob.class,
			Connection.class);

	private volatile String type;
	private volatile String methodPrefix;
	private volatile Throwable failure;
	private volatile boolean afterExecute;
	private volatile boolean executed;
	private volatile int skip;
	private final AtomicInteger aborted = new AtomicInteger();
	private final DataSource dataSource;

	FailingDriver(DataSource real) {
		this.dataSource = (DataSource) Proxy.newProxyInstance(FailingDriver.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					Object result = invoke(real, method, args);
					return result instanceof Connection connection ? wrap(connection, Connection.class) : result;
				});
	}

	DataSource dataSource() {
		return dataSource;
	}

	/** Fails once, at the first call of a method whose name starts with {@code methodPrefix}, on a {@code type}. */
	FailingDriver failAt(String type, String methodPrefix, Throwable failure) {
		this.type = type;
		this.methodPrefix = methodPrefix;
		this.failure = failure;
		this.afterExecute = false;
		this.skip = 0;
		return this;
	}

	/**
	 * As {@link #failAt}, but only once a statement ran, while the call reads its results: the call's log reads a
	 * value it sends before that, and ignores a failure there.
	 */
	FailingDriver failReadingAt(String type, String methodPrefix, Throwable failure) {
		return failReadingAt(type, methodPrefix, failure, 0);
	}

	/** As above, letting the first {@code skip} such calls through, such as the log's read of a value received. */
	FailingDriver failReadingAt(String type, String methodPrefix, Throwable failure, int skip) {
		failAt(type, methodPrefix, failure);
		this.executed = false;
		this.afterExecute = true;
		this.skip = skip;
		return this;
	}

	/** Whether the failure was thrown, which a test checks, so that it does not pass by missing the call. */
	boolean fired() {
		return failure == null;
	}

	int aborted() {
		return aborted.get();
	}

	private Object wrap(Object real, Class<?> type) {
		return Proxy.newProxyInstance(real.getClass().getClassLoader(), new Class<?>[] { type },
				new Handler(real, type));
	}

	private Object wrapIfDriverObject(Object result) {
		if (result instanceof Object[] elements && result.getClass() == Object[].class) {
			for (int i = 0; i < elements.length; i++) {
				elements[i] = wrapIfDriverObject(elements[i]);
			}
			return elements;
		}
		for (Class<?> wrapped : WRAPPED) {
			if (wrapped.isInstance(result) && !Proxy.isProxyClass(result.getClass())) {
				return wrap(result, wrapped);
			}
		}
		return result;
	}

	private static Object real(Object value) {
		if (value != null && Proxy.isProxyClass(value.getClass())
				&& Proxy.getInvocationHandler(value) instanceof Handler handler) {
			return handler.real;
		}
		if (value instanceof Object[] elements && value.getClass() == Object[].class) {
			Object[] reals = new Object[elements.length];
			for (int i = 0; i < elements.length; i++) {
				reals[i] = real(elements[i]);
			}
			return reals;
		}
		return value;
	}

	private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
		try {
			return method.invoke(target, args);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	private final class Handler implements InvocationHandler {

		private final Object real;
		private final Class<?> type;

		Handler(Object real, Class<?> type) {
			this.real = real;
			this.type = type;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "equals" -> proxy == args[0];
					case "hashCode" -> System.identityHashCode(proxy);
					default -> "failing " + real;
				};
			}
			Throwable thrown = failure;
			if (thrown != null && (!afterExecute || executed) && type.getSimpleName().equals(FailingDriver.this.type)
					&& method.getName().startsWith(methodPrefix)) {
				if (skip > 0) {
					skip--;
				} else {
					failure = null;
					throw thrown;
				}
			}
			if (method.getName().equals("abort")) {
				aborted.incrementAndGet();
			}
			Object[] reals = args == null ? null : new Object[args.length];
			for (int i = 0; args != null && i < args.length; i++) {
				reals[i] = real(args[i]);
			}
			Object result = FailingDriver.invoke(real, method, reals);
			if (method.getName().equals("execute") && real instanceof Statement) {
				executed = true;
			}
			if (method.getName().equals("unwrap") && result != null && ((Class<?>) args[0]).isInterface()
					&& !((Class<?>) args[0]).isInstance(proxy)) {
				return wrap(result, (Class<?>) args[0]);
			}
			return wrapIfDriverObject(result);
		}
	}
}
