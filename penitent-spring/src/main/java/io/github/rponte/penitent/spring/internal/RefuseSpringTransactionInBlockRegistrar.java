package io.github.rponte.penitent.spring.internal;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.transaction.ConfigurableTransactionManager;

/**
 * Adds {@link RefuseSpringTransactionInBlock}, once, to every transaction manager of the context that
 * takes listeners: the ones the application declares and the ones Spring Boot creates. A manager
 * created with {@code new} outside the context is out of its reach.
 *
 * <p>The listener is not a bean on purpose: Spring Boot would add it too, to the managers it creates.
 */
public final class RefuseSpringTransactionInBlockRegistrar implements BeanPostProcessor {

	private static final String NAME = RefuseSpringTransactionInBlockRegistrar.class.getName();

	private static final RefuseSpringTransactionInBlock REFUSAL = new RefuseSpringTransactionInBlock();

	/** Registers it in the context, once, however many times it is asked. */
	public static void registerIn(BeanDefinitionRegistry registry) {
		if (registry.containsBeanDefinition(NAME)) {
			return;
		}
		AbstractBeanDefinition definition = BeanDefinitionBuilder
				.genericBeanDefinition(RefuseSpringTransactionInBlockRegistrar.class)
				.setRole(BeanDefinition.ROLE_INFRASTRUCTURE)
				.getBeanDefinition();
		registry.registerBeanDefinition(NAME, definition);
	}

	/** Before initialization: the manager itself, not a proxy that might wrap it. */
	@Override
	public Object postProcessBeforeInitialization(Object bean, String beanName) {
		if (bean instanceof ConfigurableTransactionManager manager && manager.getTransactionExecutionListeners()
				.stream().noneMatch(RefuseSpringTransactionInBlock.class::isInstance)) {
			manager.addListener(REFUSAL);
		}
		return bean;
	}
}
