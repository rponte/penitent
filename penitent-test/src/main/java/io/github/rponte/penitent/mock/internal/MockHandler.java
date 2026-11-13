package io.github.rponte.penitent.mock.internal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.rponte.penitent.PlsqlOut;

/**
 * Behind a mock: a call is only recorded, for {@code when}, or checked, when a {@code verify} waits for it. It
 * never reaches the database, and returns the default value of its type.
 */
public final class MockHandler implements InvocationHandler {

	private final MockSession session;
	private final Class<?> type;

	public MockHandler(MockSession session, Class<?> type) {
		this.session = session;
		this.type = type;
	}

	public MockSession session() {
		return session;
	}

	public Class<?> type() {
		return type;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
		if (method.getDeclaringClass() == Object.class) {
			return switch (method.getName()) {
				case "toString" -> "mock of " + type.getName();
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == args[0];
				default -> throw new UnsupportedOperationException(method.getName());
			};
		}
		if (method.isDefault()) {
			return InvocationHandler.invokeDefault(proxy, method, args);
		}
		Recording.refuseUnused();
		Object[] arguments = args == null ? new Object[0] : args;
		Invocation invocation = new Invocation(this, method, matchersOf(method, arguments));
		Recording.Verification verification = Recording.takeVerification(proxy);
		if (verification != null) {
			session.verify(invocation, verification.times());
		} else {
			Recording.last(invocation);
		}
		return defaultOf(method.getReturnType());
	}

	private static List<Matcher> matchersOf(Method method, Object[] arguments) {
		List<Matcher> given = Recording.takeMatchers();
		if (given.isEmpty()) {
			List<Matcher> matchers = new ArrayList<>();
			for (Object argument : arguments) {
				matchers.add(argument instanceof PlsqlOut<?> ? new Matcher.Any() : new Matcher.Equal(argument));
			}
			return matchers;
		}
		if (given.size() != arguments.length) {
			Recording.reset();
			throw new IllegalStateException(method.getName() + " takes " + arguments.length + " arguments, and was given "
					+ given.size() + " matchers: a call uses matchers for all of its arguments or for none, so write"
					+ " each plain value as eq(value)");
		}
		return given;
	}

	private static Object defaultOf(Class<?> type) {
		if (!type.isPrimitive() || type == void.class) {
			return null;
		}
		if (type == boolean.class) {
			return false;
		}
		if (type == char.class) {
			return '\0';
		}
		if (type == long.class) {
			return 0L;
		}
		if (type == double.class) {
			return 0d;
		}
		if (type == float.class) {
			return 0f;
		}
		if (type == short.class) {
			return (short) 0;
		}
		if (type == byte.class) {
			return (byte) 0;
		}
		return 0;
	}
}
