package io.github.rponte.penitent.spring.internal;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;

import io.github.rponte.penitent.internal.proxy.ProxyIntegration;

/**
 * Spring's AOP proxies, as the core sees them. Spring wraps an interface's bean in a proxy of its own
 * for {@code @Transactional}, {@code @Validated}, Resilience4j or the application's advice; this lets
 * {@code validate} reach the library's proxy inside, and gives a view of {@code using} the same advice.
 * Found through {@code META-INF/services}.
 */
public final class SpringProxyIntegration implements ProxyIntegration {

	/** The target of a Spring proxy with a single, fixed one: the usual singleton bean. */
	@Override
	public Object unwrap(Object proxy) {
		if (!(proxy instanceof Advised advised) || !advised.getTargetSource().isStatic()) {
			return null;
		}
		try {
			return advised.getTargetSource().getTarget();
		} catch (Exception e) {
			throw new IllegalStateException("failed to reach the target of " + proxy.getClass().getName(), e);
		}
	}

	/**
	 * Every advisor of {@code wrapper}, in the same order, around the view, Spring's transaction included:
	 * a view behaves as the bean does, so inside a block a {@code @Transactional} method is refused, as any
	 * Spring transaction there is, instead of losing its annotation without a word.
	 */
	@Override
	public Object rewrap(Object wrapper, Object view) {
		Advised advised = (Advised) wrapper;
		ProxyFactory factory = new ProxyFactory();
		factory.setInterfaces(advised.getProxiedInterfaces());
		factory.setExposeProxy(advised.isExposeProxy());
		factory.addAdvisors(advised.getAdvisors());
		factory.setTarget(view);
		return factory.getProxy(view.getClass().getClassLoader());
	}
}
