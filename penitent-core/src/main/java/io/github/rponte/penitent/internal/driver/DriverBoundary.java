package io.github.rponte.penitent.internal.driver;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Struct;
import java.util.List;

/**
 * The boundary between the library and the Oracle driver, inside a call (#147). JDBC lets a driver report a failure
 * only as a {@code SQLException}, but the Oracle driver sometimes throws a runtime exception or an
 * {@code AssertionError} of its own. The call's connection is wrapped here, in a proxy that passes every method on to
 * the driver, as HikariCP wraps its connections, and so is each JDBC object the driver hands back: a statement, a
 * cursor's {@code ResultSet}, a {@code Struct}, an {@code Array}, a LOB, the driver's own connection that
 * {@code unwrap} gives. What comes out of the driver as a runtime exception or an {@code AssertionError} becomes a
 * {@link DriverFailure}, with the original as its cause; a {@code SQLException} and any other {@code Error} go as they
 * are.
 *
 * <p><b>Why at the objects, and not around each phase.</b> In binding and reading, the driver's calls are mixed with
 * the library's code and the application's converters: a {@code try} around a whole phase would take a converter's
 * exception, or a bug of the library, for the driver's. A converter receives values, not the driver's objects, so
 * nothing but the driver runs inside the proxy, as nothing but the driver runs inside {@code execute()}. The rule holds
 * by construction, for every call to the driver, today's and tomorrow's.
 *
 * <p><b>What it does not cover.</b> A JDK proxy implements interfaces. The driver's value classes, such as
 * {@code oracle.sql.TIMESTAMPTZ} or {@code oracle.sql.STRUCT}, are classes, so the few calls on them go through
 * {@link #guarded}. Code that tests the driver's class of an object, as {@code instanceof oracle.sql.STRUCT} does,
 * tests {@link #real} of it.
 *
 * <p>A proxy exists only inside a call: the application's {@code Connection} and {@code DataSource} are not touched, and
 * no driver object leaves a call, since LOBs, cursors, object types and collections are copied into Java values. The
 * driver receives its own objects back: a proxy given as an argument, or inside an {@code Object[]}, is unwrapped
 * first.
 */
public final class DriverBoundary {

	/** The JDBC types a proxy stands for, the more specific first. */
	private static final List<Class<?>> WRAPPED = List.of(CallableStatement.class, PreparedStatement.class,
			Statement.class, ResultSet.class, Struct.class, Array.class, NClob.class, Clob.class, Blob.class,
			SQLXML.class, Ref.class, ResultSetMetaData.class, ParameterMetaData.class, DatabaseMetaData.class,
			Connection.class);

	private DriverBoundary() {
	}

	/** The call's connection, behind the boundary; a connection already behind it is returned as it is. */
	public static Connection wrap(Connection connection) {
		if (connection == null || isBoundary(connection)) {
			return connection;
		}
		return (Connection) proxy(connection, Connection.class, connection);
	}

	/** The driver's own object behind a proxy of the boundary, or {@code value} itself. */
	@SuppressWarnings("unchecked")
	public static <T> T real(T value) {
		if (value != null && Proxy.isProxyClass(value.getClass())
				&& Proxy.getInvocationHandler(value) instanceof Handler handler) {
			return (T) handler.real;
		}
		return value;
	}

	/**
	 * A call on one of the driver's value classes, which a proxy cannot stand for, such as {@code oracle.sql.STRUCT}:
	 * its runtime exception or {@code AssertionError} becomes a {@link DriverFailure} as at the boundary.
	 * {@code connection} is the call's, which the failure discards.
	 */
	public static <T> T guarded(String operation, Connection connection, DriverCall<T> call) throws SQLException {
		try {
			return call.call();
		} catch (DriverFailure e) {
			throw e;
		} catch (RuntimeException | AssertionError e) {
			throw new DriverFailure(operation, e, rootOf(connection));
		}
	}

	/** A call on one of the driver's objects, for {@link #guarded}. */
	@FunctionalInterface
	public interface DriverCall<T> {
		T call() throws SQLException;
	}

	/** A public interface of JDBC or of the Oracle driver, which {@code unwrap} gives behind the boundary. */
	private static boolean isDriversInterface(Class<?> type) {
		String name = type.getName();
		return type.isInterface() && java.lang.reflect.Modifier.isPublic(type.getModifiers())
				&& (name.startsWith("java.sql.") || name.startsWith("javax.sql.") || name.startsWith("oracle."));
	}

	private static boolean isBoundary(Object value) {
		return Proxy.isProxyClass(value.getClass()) && Proxy.getInvocationHandler(value) instanceof Handler;
	}

	private static Connection rootOf(Connection connection) {
		if (connection != null && isBoundary(connection)) {
			return ((Handler) Proxy.getInvocationHandler(connection)).connection;
		}
		return connection;
	}

	private static Object proxy(Object real, Class<?> type, Connection connection) {
		return Proxy.newProxyInstance(loaderOf(real, type), new Class<?>[] { type }, new Handler(real, type, connection));
	}

	/** The driver's loader sees both the JDBC interfaces and its own; the JDK's interfaces need no loader of theirs. */
	private static ClassLoader loaderOf(Object real, Class<?> type) {
		ClassLoader loader = real.getClass().getClassLoader();
		return loader != null ? loader : type.getClassLoader();
	}

	private static Object[] reals(Object[] args) {
		if (args == null) {
			return null;
		}
		Object[] reals = args.clone();
		for (int i = 0; i < reals.length; i++) {
			reals[i] = realDeep(reals[i]);
		}
		return reals;
	}

	/** Unwraps a proxy, and the proxies inside an {@code Object[]}, such as a {@code Struct}'s attributes. */
	private static Object realDeep(Object value) {
		if (value instanceof Object[] elements && !elements.getClass().getComponentType().isPrimitive()) {
			Object[] reals = null;
			for (int i = 0; i < elements.length; i++) {
				Object real = realDeep(elements[i]);
				if (real != elements[i]) {
					if (reals == null) {
						reals = elements.clone();
					}
					reals[i] = real;
				}
			}
			return reals == null ? elements : reals;
		}
		return real(value);
	}

	private static final class Handler implements InvocationHandler {

		private final Object real;
		private final Class<?> type;
		private final Connection connection;

		Handler(Object real, Class<?> type, Connection connection) {
			this.real = real;
			this.type = type;
			this.connection = connection;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				return switch (method.getName()) {
					case "equals" -> proxy == args[0] || real.equals(real(args[0]));
					case "hashCode" -> real.hashCode();
					default -> real.toString();
				};
			}
			Object result;
			try {
				result = method.invoke(real, reals(args));
			} catch (InvocationTargetException e) {
				Throwable failure = e.getCause();
				if (failure instanceof RuntimeException || failure instanceof AssertionError) {
					throw new DriverFailure(type.getSimpleName() + "." + method.getName(), failure, connection);
				}
				throw failure;
			}
			if (method.getName().equals("unwrap") && result != null && args[0] instanceof Class<?> wanted
					&& isDriversInterface(wanted) && !isBoundary(result)) {
				// the driver's own connection or statement, as OracleArrays and the time zones use it. Another
				// library's interface, such as the mocks' integration with the core, is not the driver's: as it is
				return proxy(result, wanted, connection);
			}
			return wrapped(result);
		}

		/** A JDBC object the driver hands back, behind the boundary too; an {@code Object[]}'s elements as well. */
		private Object wrapped(Object result) {
			// a JDK proxy of someone else's, such as a pool's or Spring's, still stands for the driver: only the
			// boundary's own is not wrapped again
			if (result == null || isBoundary(result)) {
				return result;
			}
			if (result instanceof Object[] elements && !elements.getClass().getComponentType().isPrimitive()) {
				// a copy, so that the driver's own array, which it may keep, never holds a proxy
				Object[] copy = null;
				for (int i = 0; i < elements.length; i++) {
					Object element = wrapped(elements[i]);
					if (element != elements[i] && elements.getClass().getComponentType().isInstance(element)) {
						if (copy == null) {
							copy = elements.clone();
						}
						copy[i] = element;
					}
				}
				return copy == null ? elements : copy;
			}
			for (Class<?> wrapped : WRAPPED) {
				if (wrapped.isInstance(result)) {
					return proxy(result, wrapped, connection);
				}
			}
			return result;
		}
	}
}
