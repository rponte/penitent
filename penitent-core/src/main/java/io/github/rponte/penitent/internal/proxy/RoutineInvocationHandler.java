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
import io.github.rponte.penitent.internal.metadata.Dictionary.SqlFailure;
import io.github.rponte.penitent.internal.metadata.ReturnType;

/**
 * The handler behind each proxy that {@code PlsqlPackages} creates: it answers the default and
 * {@link Object} methods itself, and turns every other one into a call, of a routine or of an
 * anonymous block, on a connection of its own.
 */
public final class RoutineInvocationHandler implements InvocationHandler {

	private final Class<?> type;
	private final DataSource dataSource;
	private final PlsqlExceptionTranslator translator;
	private final RoutineCall routines;
	private final BlockCall blocks;

	/** @param defaultBind the bind of an interface that does not declare one */
	public RoutineInvocationHandler(Class<?> type, DataSource dataSource, PlsqlExceptionTranslator translator,
			PlsqlConverters converters, PlsqlBind defaultBind) {
		this.type = type;
		this.dataSource = dataSource;
		this.translator = translator;
		TypeCache types = new TypeCache(converters);
		ValueEncoder encoder = new ValueEncoder(types);
		ValueDecoder decoder = new ValueDecoder(types);
		this.routines = new RoutineCall(type, defaultBind, types, encoder, decoder);
		this.blocks = new BlockCall(types, encoder, decoder);
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
		ReturnType.requireNoOptionalParameter(method);
		boolean lobs = TemporaryLobs.open();
		try (Connection connection = dataSource.getConnection()) {
			PlsqlBlock block = method.getAnnotation(PlsqlBlock.class);
			Object result = block != null
					? blocks.call(method, block, arguments, connection)
					: routines.call(method, arguments, connection);
			return ReturnType.wrap(method, result);
		} catch (SqlFailure e) {
			throw translator.translate(e.failure());
		} catch (SQLException e) {
			throw translator.translate(new PlsqlFailure(null, e, PlsqlFailure.Phase.CONNECTION));
		} finally {
			TemporaryLobs.close(lobs);
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
		try (Connection connection = dataSource.getConnection()) {
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
					String message = e.getMessage().startsWith(method.getName()) ? e.getMessage()
							: method.getName() + ": " + e.getMessage();
					errors.add(new PlsqlMappingException(message, e));
				}
			}
		} catch (SqlFailure e) {
			throw translator.translate(e.failure());
		} catch (SQLException e) {
			throw translator.translate(new PlsqlFailure(null, e, PlsqlFailure.Phase.CONNECTION));
		}
		if (errors.size() == 1) {
			throw new PlsqlMappingException(type.getName() + "." + errors.get(0).getMessage(),
					errors.get(0).getCause());
		}
		if (!errors.isEmpty()) {
			PlsqlMappingException all = new PlsqlMappingException(errors.size() + " methods of " + type.getName()
					+ " do not match the database:" + errors.stream().map(e -> "\n  - " + e.getMessage())
							.collect(java.util.stream.Collectors.joining()));
			errors.forEach(e -> all.addSuppressed(e.getCause()));
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
	private static Object invokeObjectMethod(Object proxy, Method method, Object[] args) {
		return switch (method.getName()) {
			case "equals" -> proxy == args[0];
			case "hashCode" -> System.identityHashCode(proxy);
			default -> "PlsqlPackages proxy for " + proxy.getClass().getInterfaces()[0].getName();
		};
	}
}
