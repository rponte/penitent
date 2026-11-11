package io.github.rponte.penitent.spring.internal;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.interceptor.TransactionInterceptor;

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
	 * Every advisor of {@code wrapper}, in the same order, around the view, except Spring's transaction:
	 * the view runs in the transaction of its connection, the block's or the application's, and a Spring
	 * transaction there would take another connection, or be refused inside a block.
	 */
	@Override
	public Object rewrap(Object wrapper, Object view) {
		Advised advised = (Advised) wrapper;
		ProxyFactory factory = new ProxyFactory();
		factory.setInterfaces(advised.getProxiedInterfaces());
		factory.setExposeProxy(advised.isExposeProxy());
		for (Advisor advisor : advised.getAdvisors()) {
			if (!(advisor.getAdvice() instanceof TransactionInterceptor)) {
				factory.addAdvisor(advisor);
			}
		}
		factory.setTarget(view);
		return factory.getProxy(view.getClass().getClassLoader());
	}
}
