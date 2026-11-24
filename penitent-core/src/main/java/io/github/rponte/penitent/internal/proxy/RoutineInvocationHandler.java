package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.internal.binding.TemporaryLobs;
import io.github.rponte.penitent.internal.driver.DriverBoundary;
import io.github.rponte.penitent.internal.driver.DriverFailure;
import io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure;
import io.github.rponte.penitent.internal.metadata.ReturnType;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;

/**
 * The handler behind each proxy that {@code PlsqlPackages} creates: it answers the default and
 * {@link Object} methods itself, and turns every other one into a call, of a routine or of an
 * anonymous block, on the connection its {@link Connections} hand out.
 *
 * <p>What it reads from the dictionary lives in {@code routines} and {@code blocks}; a handler derived
 * for another connection shares them, so a view bound to a connection reads nothing again.
 */
public final class RoutineInvocationHandler implements InvocationHandler {

	private final Class<?> type;
	private final Connections connections;
	private final PlsqlExceptionTranslator translator;
	private final RoutineCall routines;
	private final BlockCall blocks;
	private final ValueEncoder encoder;

	/** @param defaultBind the bind of an interface that does not declare one */
	public RoutineInvocationHandler(Class<?> type, DataSource dataSource, PlsqlExceptionTranslator translator,
			PlsqlConverters converters, PlsqlBind defaultBind) {
		this.type = type;
		this.connections = new Connections.FromDataSource(dataSource);
		this.translator = translator;
		TypeCache types = new TypeCache(converters);
		this.encoder = new ValueEncoder(types);
		ValueDecoder decoder = new ValueDecoder(types);
		this.routines = new RoutineCall(type, defaultBind, types, encoder, decoder);
		this.blocks = new BlockCall(types, encoder, decoder);
	}

	private RoutineInvocationHandler(RoutineInvocationHandler original, Connections connections) {
		this.type = original.type;
		this.connections = connections;
		this.translator = original.translator;
		this.routines = original.routines;
		this.blocks = original.blocks;
		this.encoder = original.encoder;
	}

	public Class<?> type() {
		return type;
	}

	/**
	 * The same handler, with the same caches, settings and translator, bound to {@code connection}:
	 * every call runs on it, and it is never closed, committed or rolled back.
	 */
	public RoutineInvocationHandler boundTo(Connection connection, Runnable guard) {
		return new RoutineInvocationHandler(this, new Connections.Bound(connection, guard));
	}

	/**
	 * A proxy of the {@code DataSource} a {@code PlsqlTransaction} of this thread is open on would take
	 * another connection, and run outside the transaction: refused, so that it cannot go unnoticed.
	 */
	private void refuseOutsideTheTransaction() {
		if (connections instanceof Connections.FromDataSource fromDataSource
				&& TransactionScope.isOpenOn(fromDataSource.dataSource())) {
			throw new IllegalStateException(type.getName() + " uses the DataSource of the PlsqlTransaction open on this"
					+ " thread, and would run outside it: call it through tx.using(...)");
		}
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (method.isDefault()) {
			return invokeDefault(proxy, method, args);
		}
		if (method.getDeclaringClass() == Object.class) {
			return invokeObjectMethod(proxy, method, args);
		}

		Object[] arguments = args == null ? new Object[0] : args;
		try {
			ReturnType.requireNoOptionalParameter(method);
		} catch (PlsqlMappingException e) {
			throw MappingMessages.of(method, e);
		}
		refuseOutsideTheTransaction();
		boolean lobs = TemporaryLobs.open();
		try (Connections.Lease lease = connections.acquire()) {
			// behind the boundary, where a runtime exception of the driver is told from the library's (#147)
			Connection connection = DriverBoundary.wrap(lease.connection());
			PlsqlBlock block = method.getAnnotation(PlsqlBlock.class);
			Object result = block != null
					? blocks.call(method, block, arguments, connection)
					: routines.call(method, arguments, connection);
			return ReturnType.wrap(method, result);
		} catch (PlsqlMappingException e) {
			throw MappingMessages.of(method, e);
		} catch (DriverFailure e) {
			throw driverFailure(method, e);
		} catch (SqlFailure e) {
			throw translator.translate(e.failure());
		} catch (SQLException e) {
			throw translator.translate(new PlsqlFailure(null, e, PlsqlFailure.Phase.CONNECTION));
		} finally {
			TemporaryLobs.close(lobs);
		}
	}

	/**
	 * The routine a call of {@code method} would run, read from the dictionary as the call reads it, and
	 * cached as the call caches it: for a tool that has to know the routine behind a method, such as the
	 * mocks of {@code penitent-test}.
	 */
	public RoutineMetadata describe(Method method) {
		try (Connections.Lease lease = connections.acquire()) {
			return routines.describeOnce(method, DriverBoundary.wrap(lease.connection()));
		} catch (PlsqlMappingException e) {
			throw MappingMessages.of(method, e);
		} catch (DriverFailure e) {
			throw driverFailure(method, e);
		} catch (SqlFailure e) {
			throw translator.translate(e.failure());
		} catch (SQLException e) {
			throw translator.translate(new PlsqlFailure(null, e, PlsqlFailure.Phase.CONNECTION));
		}
	}

	/**
	 * {@code value} as the return value of {@code method}, encoded as a call would decode it backwards, on
	 * {@code connection}: through the method's converter and {@code @PlsqlBoolean}, to a {@code Struct} for an object
	 * type, an {@code Array} for a collection, and the driver's value for a scalar, without the temporary LOB a call
	 * would bind. For a tool that writes the value elsewhere, such as the mocks of {@code penitent-test}, which write
	 * it as PL/SQL.
	 */
	public Object encodeReturn(Method method, Object value, Connection connection) {
		return encoded(() -> routines.encodeReturn(method, value, connection), method);
	}

	/**
	 * {@code value}, given for the {@code index}-th Java parameter of {@code method}, as a call would send it to the
	 * Oracle parameter that Java parameter binds, by position or by name, with its converter and
	 * {@code @PlsqlBoolean}; as {@link #encodeReturn} for the rest.
	 */
	public Object encodeParameter(Method method, int index, Object value, Connection connection) {
		return encoded(() -> routines.encodeParameter(method, index, value, connection), method);
	}

	/**
	 * A failure of the driver that no phase of the call turned into the library's exception, such as one while the
	 * routine is described outside the dictionary's queries: a mapping failure while binding, and otherwise the
	 * driver's own, with the connection discarded.
	 */
	private RuntimeException driverFailure(Method method, DriverFailure e) {
		if (e.inBinding()) {
			PlsqlMappingException mapping = e.ofBinding("a value");
			return method == null ? mapping : MappingMessages.of(method, mapping);
		}
		return translator.translate(e.ofCall(null, PlsqlFailure.Phase.PREPARATION).failure());
	}

	@FunctionalInterface
	private interface Encoding {
		Object run() throws SQLException;
	}

	private Object encoded(Encoding encoding, Method method) {
		try {
			return encoding.run();
		} catch (PlsqlMappingException e) {
			throw MappingMessages.of(method, e);
		} catch (SqlFailure e) {
			throw translator.translate(e.failure());
		} catch (SQLException e) {
			throw translator.translate(new PlsqlFailure(null, e, PlsqlFailure.Phase.PREPARATION));
		}
	}

	/**
	 * Checks every method of the interface against the database, on one connection, and leaves what
	 * it read in the caches. The mapping errors are collected, one per method, and thrown together: at
	 * startup, fixing one only to find the next is what this is meant to avoid. A database failure is
	 * not a mapping error, and stops the validation as it would stop a call.
	 */
	public void validate() {
		List<Method> methods = java.util.Arrays.stream(type.getMethods())
				.filter(m -> !m.isDefault() && !java.lang.reflect.Modifier.isStatic(m.getModifiers()))
				.sorted(java.util.Comparator.comparing(Method::getName)
						.thenComparing(m -> java.util.Arrays.toString(m.getParameterTypes())))
				.toList();
		List<PlsqlMappingException> errors = new java.util.ArrayList<>();
		refuseOutsideTheTransaction();
		try (Connections.Lease lease = connections.acquire()) {
			Connection connection = DriverBoundary.wrap(lease.connection());
			for (Method method : methods) {
				try {
					ReturnType.requireNoOptionalParameter(method);
					PlsqlBlock block = method.getAnnotation(PlsqlBlock.class);
					if (block != null) {
						blocks.validate(method, block);
					} else {
						routines.validate(method, connection);
					}
				} catch (PlsqlMappingException e) {
					errors.add(MappingMessages.of(method, e));
				}
			}
		} catch (DriverFailure e) {
			throw driverFailure(null, e);
		} catch (SqlFailure e) {
			throw translator.translate(e.failure());
		} catch (SQLException e) {
			throw translator.translate(new PlsqlFailure(null, e, PlsqlFailure.Phase.CONNECTION));
		}
		if (errors.size() == 1) {
			throw new PlsqlMappingException(type.getName() + "." + errors.get(0).getMessage(), errors.get(0));
		}
		if (!errors.isEmpty()) {
			PlsqlMappingException all = new PlsqlMappingException(errors.size() + " methods of " + type.getName()
					+ " do not match the database:" + errors.stream().map(e -> "\n  - " + e.getMessage())
							.collect(java.util.stream.Collectors.joining()));
			errors.forEach(all::addSuppressed);
			throw all;
		}
	}

	/**
	 * {@link InvocationHandler#invokeDefault} only reaches an interface this library can access, that
	 * is, a public one. Any other is called through a lookup with the interface's own access: on the
	 * classpath that always works, and in a module it needs the package to be open to the library.
	 * Whatever the default method throws comes out as it is, without a wrapper.
	 */
	private static Object invokeDefault(Object proxy, Method method, Object[] args) throws Throwable {
		Class<?> declaring = method.getDeclaringClass();
		if (isAccessible(declaring)) {
			return InvocationHandler.invokeDefault(proxy, method, args);
		}
		java.lang.invoke.MethodHandle handle;
		try {
			handle = java.lang.invoke.MethodHandles.privateLookupIn(declaring, java.lang.invoke.MethodHandles.lookup())
					.unreflectSpecial(method, declaring);
		} catch (IllegalAccessException e) {
			throw new PlsqlMappingException(declaring.getName() + "." + method.getName() + " is a default method of"
					+ " a non-public interface, and its package " + declaring.getPackageName() + " is not open to"
					+ " the library; open it to the library's module, or make the interface public", e);
		}
		return handle.bindTo(proxy).invokeWithArguments(args == null ? new Object[0] : args);
	}

	/** Public, and nested only in public types: otherwise the library cannot see it. */
	private static boolean isAccessible(Class<?> type) {
		for (Class<?> current = type; current != null; current = current.getDeclaringClass()) {
			if (!java.lang.reflect.Modifier.isPublic(current.getModifiers())) {
				return false;
			}
		}
		return true;
	}

	/** The identity is the proxy's, which is what the caller sees, not this handler's. */
	private Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
		return switch (method.getName()) {
			case "equals" -> proxy == args[0];
			case "hashCode" -> System.identityHashCode(proxy);
			default -> "PlsqlPackages proxy for " + proxy.getClass().getInterfaces()[0].getName()
					+ (connections instanceof Connections.Bound ? ", bound to a connection" : "");
		};
	}
}
