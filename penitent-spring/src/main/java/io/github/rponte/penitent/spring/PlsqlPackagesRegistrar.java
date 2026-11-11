package io.github.rponte.penitent.spring;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.ClassUtils;

import io.github.rponte.penitent.spring.internal.RefuseSpringTransactionInBlockRegistrar;

/**
 * Reads {@link EnablePlsqlPackages} and hands its packages to {@link PlsqlPackagesScanner}. With
 * {@link EnablePlsqlPackages#validateOnStartup()}, it also registers a {@link PlsqlPackagesValidator}
 * for the interfaces found. It also makes the context's transaction managers refuse a Spring
 * transaction inside a {@code PlsqlTransaction}: see {@link RefuseSpringTransactionInBlockRegistrar}.
 *
 * <p>Imported by {@link EnablePlsqlPackages}; an application does not use it directly.
 */
public final class PlsqlPackagesRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware,
		EnvironmentAware {

	private ResourceLoader resourceLoader;
	private Environment environment;

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
		Map<String, Object> attributes = metadata.getAnnotationAttributes(EnablePlsqlPackages.class.getName());
		PlsqlPackagesScanner.markScannedByAnnotation(registry);
		RefuseSpringTransactionInBlockRegistrar.registerIn(registry);
		Set<String> found = new PlsqlPackagesScanner(registry, resourceLoader, environment).scan(
				basePackages(attributes, metadata), (Boolean) attributes.get("considerNested"),
				(String) attributes.get("dataSource"));
		if ((Boolean) attributes.get("validateOnStartup")) {
			registerValidator(metadata.getClassName(), found, registry);
		}
	}

	/**
	 * One validator per annotation, for the interfaces it found, including those another annotation
	 * registered first: asking for validation covers everything this one scans.
	 */
	private static void registerValidator(String configuration, Set<String> interfaces,
			BeanDefinitionRegistry registry) {
		AbstractBeanDefinition definition = BeanDefinitionBuilder
				.genericBeanDefinition(PlsqlPackagesValidator.class)
				.addConstructorArgValue(List.copyOf(interfaces))
				.getBeanDefinition();
		registry.registerBeanDefinition(PlsqlPackagesValidator.class.getName() + "#" + configuration, definition);
	}

	private static Set<String> basePackages(Map<String, Object> attributes, AnnotationMetadata metadata) {
		Set<String> packages = new LinkedHashSet<>();
		for (String name : (String[]) attributes.get("basePackages")) {
			if (!name.isBlank()) {
				packages.add(name.trim());
			}
		}
		for (Class<?> type : (Class<?>[]) attributes.get("basePackageClasses")) {
			packages.add(type.getPackageName());
		}
		if (packages.isEmpty()) {
			packages.add(ClassUtils.getPackageName(metadata.getClassName()));
		}
		return packages;
	}
}
