package io.github.rponte.penitent.internal.proxy;

import java.util.List;
import java.util.ServiceLoader;

/**
 * What a framework that wraps the library's proxies in proxies of its own, such as Spring's AOP, tells
 * the core about them, so that {@code validate} and the views of {@code using} work on the object the
 * application holds. The core does not know that framework: the module that integrates it, such as
 * {@code penitent-spring}, registers an implementation in {@code META-INF/services}, and
 * {@link ServiceLoader} finds it on the classpath.
 *
 * <p>Not part of the API: it changes with the modules that implement it, which are released together
 * with the core.
 */
public interface ProxyIntegration {

	/** The object {@code proxy} wraps, when it is a wrapper of this framework; otherwise {@code null}. */
	Object unwrap(Object proxy);

	/**
	 * {@code view} wrapped as {@code wrapper} wraps its target, with the same behavior around it, except a
	 * transaction of the framework's own: a view runs in the transaction of its connection.
	 */
	Object rewrap(Object wrapper, Object view);

	/** The implementations on the classpath, found once. */
	static List<ProxyIntegration> all() {
		return Loaded.ALL;
	}

	/** Holds the implementations, loaded on first use. */
	final class Loaded {

		private static final List<ProxyIntegration> ALL = ServiceLoader
				.load(ProxyIntegration.class, ProxyIntegration.class.getClassLoader())
				.stream()
				.map(ServiceLoader.Provider::get)
				.toList();

		private Loaded() {
		}
	}
}
