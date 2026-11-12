package io.github.rponte.penitent.mock.spring;

import javax.sql.DataSource;

import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

import io.github.rponte.penitent.mock.PlsqlMocks;

/**
 * Wraps every {@code DataSource} bean of the context with {@link PlsqlMocks#wrap}, and adds the {@link PlsqlMocks}
 * bean the tests inject, on the {@code DataSource} the annotation names, or the only or primary one. Two of these are
 * equal when they name the same {@code DataSource}, so that Spring caches their contexts together.
 */
final class PlsqlMocksContextCustomizer implements ContextCustomizer {

	static final String MOCKS = "io.github.rponte.penitent.mock.spring.PlsqlMocks";
	private static final String WRAPPER = "io.github.rponte.penitent.mock.spring.WrapDataSources";

	private final String dataSource;

	PlsqlMocksContextCustomizer(String dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration configuration) {
		BeanDefinitionRegistry registry = (BeanDefinitionRegistry) context.getBeanFactory();
		RootBeanDefinition wrapper = new RootBeanDefinition(WrapDataSources.class);
		wrapper.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
		registry.registerBeanDefinition(WRAPPER, wrapper);

		ConfigurableListableBeanFactory beans = context.getBeanFactory();
		RootBeanDefinition mocks = new RootBeanDefinition(PlsqlMocks.class, () -> PlsqlMocks.on(dataSourceOf(beans)));
		registry.registerBeanDefinition(MOCKS, mocks);
	}

	private DataSource dataSourceOf(ConfigurableListableBeanFactory beans) {
		if (!dataSource.isEmpty()) {
			return beans.getBean(dataSource, DataSource.class);
		}
		try {
			return beans.getBean(DataSource.class);
		} catch (NoUniqueBeanDefinitionException e) {
			throw new IllegalStateException("the context has " + e.getNumberOfBeansFound() + " DataSource beans, and"
					+ " none is primary: name the one the mocks use, with @EnablePlsqlMocks(dataSource = \"...\")", e);
		}
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof PlsqlMocksContextCustomizer customizer && customizer.dataSource.equals(dataSource);
	}

	@Override
	public int hashCode() {
		return dataSource.hashCode();
	}

	/** Wraps each {@code DataSource} bean, so that the application's connections enter the test's edition. */
	static final class WrapDataSources implements BeanPostProcessor {
		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			return bean instanceof DataSource dataSource ? PlsqlMocks.wrap(dataSource) : bean;
		}
	}
}
