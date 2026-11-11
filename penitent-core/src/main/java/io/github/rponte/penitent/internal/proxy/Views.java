package io.github.rponte.penitent.internal.proxy;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Views of a proxy bound to a connection, for {@code PlsqlPackages.using} and {@code PlsqlTransaction.using}.
 *
 * <p>The object given may be the library's proxy wrapped by a framework, such as Spring for
 * {@code @Validated}: each {@link ProxyIntegration} unwraps its layer down to the library's proxy, and
 * wraps the view again, layer by layer, so the view keeps the behavior the application gave it.
 */
public final class Views {

	private Views() {
	}

	/** A proxy of the same interface, sharing {@code proxy}'s caches, that runs every call on {@code connection}. */
	public static <T> T bind(T proxy, Connection connection, Runnable guard) {
		Deque<Layer> layers = new ArrayDeque<>();
		RoutineInvocationHandler handler = unwrap(proxy, layers);
		Class<?> type = handler.type();
		Object view = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
				handler.boundTo(connection, guard));
		for (Layer layer : layers) {
			view = layer.integration().rewrap(layer.wrapper(), view);
		}
		@SuppressWarnings("unchecked")
		T bound = (T) view;
		return bound;
	}

	/** @throws IllegalArgumentException if {@code proxy} was not created by this library, nor wraps one */
	public static RoutineInvocationHandler handlerOf(Object proxy) {
		return unwrap(proxy, new ArrayDeque<>());
	}

	/** The library's handler inside {@code proxy}, pushing each wrapper met on the way, innermost first. */
	private static RoutineInvocationHandler unwrap(Object proxy, Deque<Layer> layers) {
		Object current = proxy;
		while (!(Proxy.isProxyClass(current.getClass())
				&& Proxy.getInvocationHandler(current) instanceof RoutineInvocationHandler)) {
			Layer layer = layerOf(current);
			if (layer == null) {
				throw new IllegalArgumentException(proxy.getClass().getName()
						+ " is not a proxy created by PlsqlPackages, nor a wrapper of one");
			}
			layers.push(layer);
			current = layer.target();
		}
		return (RoutineInvocationHandler) Proxy.getInvocationHandler(current);
	}

	private static Layer layerOf(Object wrapper) {
		for (ProxyIntegration integration : ProxyIntegration.all()) {
			Object target = integration.unwrap(wrapper);
			if (target != null) {
				return new Layer(integration, wrapper, target);
			}
		}
		return null;
	}

	private record Layer(ProxyIntegration integration, Object wrapper, Object target) {
	}
}
