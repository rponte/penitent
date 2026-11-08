package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.Proxy;
import java.sql.Connection;

/** Views of a proxy bound to a connection, for {@code PlsqlPackages.using} and {@code PlsqlTransaction.using}. */
public final class Views {

	private Views() {
	}

	/** A proxy of the same interface, sharing {@code proxy}'s caches, that runs every call on {@code connection}. */
	public static <T> T bind(T proxy, Connection connection, Runnable guard) {
		RoutineInvocationHandler handler = handlerOf(proxy);
		Class<?> type = handler.type();
		@SuppressWarnings("unchecked")
		T view = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
				handler.boundTo(connection, guard));
		return view;
	}

	/** @throws IllegalArgumentException if {@code proxy} was not created by this library */
	public static RoutineInvocationHandler handlerOf(Object proxy) {
		if (!Proxy.isProxyClass(proxy.getClass())
				|| !(Proxy.getInvocationHandler(proxy) instanceof RoutineInvocationHandler handler)) {
			throw new IllegalArgumentException(proxy.getClass().getName() + " is not a proxy created by PlsqlPackages");
		}
		return handler;
	}
}
