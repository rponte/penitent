package io.github.rponte.penitent.boot;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;

import io.github.rponte.penitent.spring.EnablePlsqlPackages;
import io.github.rponte.penitent.spring.PlsqlPackagesScanner;
import io.github.rponte.penitent.spring.internal.RefuseSpringTransactionInBlockRegistrar;

/**
 * Scans the application's packages, the ones Spring Data also uses: the package of the
 * {@code @SpringBootApplication} class, and any added with {@code @AutoConfigurationPackage}. It does
 * nothing when the application declares {@link EnablePlsqlPackages}: the explicit packages win.
 *
 * <p>Either way, it makes the context's transaction managers refuse a Spring transaction inside a
 * {@code PlsqlTransaction}: see {@link RefuseSpringTransactionInBlockRegistrar}.
 */
class PlsqlPackagesAutoRegistrar implements ImportBeanDefinitionRegistrar, BeanFactoryAware, ResourceLoaderAware,
		EnvironmentAware {

	private BeanFactory beanFactory;
	private ResourceLoader resourceLoader;
	private Environment environment;

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
		RefuseSpringTransactionInBlockRegistrar.registerIn(registry);
		if (!AutoConfigurationPackages.has(beanFactory) || PlsqlPackagesScanner.scannedByAnnotation(registry)) {
			return;
		}
		new PlsqlPackagesScanner(registry, resourceLoader, environment)
				.scan(AutoConfigurationPackages.get(beanFactory), false, "");
	}
}
