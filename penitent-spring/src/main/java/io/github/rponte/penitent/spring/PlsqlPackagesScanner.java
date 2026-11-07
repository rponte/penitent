package io.github.rponte.penitent.spring;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlSchema;

/**
 * Finds, in the given packages and their subpackages, the interfaces the library can implement, and
 * registers a {@link PlsqlPackageFactoryBean} for each. It is what {@link EnablePlsqlPackages} does,
 * and what the Spring Boot starter does with the application's package.
 *
 * <p>An interface is one with {@link PlsqlPackage} or {@link PlsqlSchema}, or one that declares a
 * {@link PlsqlBlock} method. The scan reads the class files without loading them, which is how it finds
 * an interface with no annotation on the type; it only sees the methods declared in the interface
 * itself, not the inherited ones.
 *
 * <p>The bean is named after the interface's fully qualified name: nobody injects it by name, and a
 * simple name could clash with another bean. An interface already registered is skipped, so two scans
 * that overlap register it once.
 */
public final class PlsqlPackagesScanner {

	/** Registered by {@link EnablePlsqlPackages}, so that the starter knows not to scan on its own. */
	private static final String ANNOTATION_MARKER = PlsqlPackagesScanner.class.getName() + ".annotation";

	private final BeanDefinitionRegistry registry;
	private final ResourceLoader resourceLoader;
	private final Environment environment;

	public PlsqlPackagesScanner(BeanDefinitionRegistry registry, ResourceLoader resourceLoader,
			Environment environment) {
		this.registry = registry;
		this.resourceLoader = resourceLoader;
		this.environment = environment;
	}

	/**
	 * @param considerNested whether interfaces nested in another type are registered too
	 * @param dataSourceName the {@code DataSource} bean the interfaces use; empty means the only one
	 * @return the fully qualified names of the interfaces found, including those already registered
	 */
	public Set<String> scan(Collection<String> basePackages, boolean considerNested, String dataSourceName) {
		ClassPathScanningCandidateComponentProvider scanner = scanner(considerNested);
		Set<String> found = new LinkedHashSet<>();
		for (String basePackage : basePackages) {
			for (BeanDefinition candidate : scanner.findCandidateComponents(basePackage)) {
				found.add(candidate.getBeanClassName());
				register(candidate.getBeanClassName(), dataSourceName);
			}
		}
		return found;
	}

	/** Whether an {@link EnablePlsqlPackages} has already scanned in this registry. */
	public static boolean scannedByAnnotation(BeanDefinitionRegistry registry) {
		return registry.containsBeanDefinition(ANNOTATION_MARKER);
	}

	static void markScannedByAnnotation(BeanDefinitionRegistry registry) {
		if (!registry.containsBeanDefinition(ANNOTATION_MARKER)) {
			registry.registerBeanDefinition(ANNOTATION_MARKER,
					BeanDefinitionBuilder.genericBeanDefinition(Marker.class).getBeanDefinition());
		}
	}

	/** Holds nothing: only its bean definition matters. */
	static final class Marker {
	}

	private ClassPathScanningCandidateComponentProvider scanner(boolean considerNested) {
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false,
				environment) {
			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
				// the default accepts only concrete classes; here it is the other way around
				AnnotationMetadata type = definition.getMetadata();
				return type.isInterface() && type.isIndependent() && (considerNested || !type.hasEnclosingClass());
			}
		};
		scanner.setResourceLoader(resourceLoader);
		scanner.addIncludeFilter(new AnnotationTypeFilter(PlsqlPackage.class, false, true));
		scanner.addIncludeFilter(new AnnotationTypeFilter(PlsqlSchema.class, false, true));
		scanner.addIncludeFilter((reader, factory) -> reader.getAnnotationMetadata()
				.hasAnnotatedMethods(PlsqlBlock.class.getName()));
		return scanner;
	}

	private void register(String interfaceName, String dataSourceName) {
		if (registry.containsBeanDefinition(interfaceName)) {
			return;
		}
		Class<?> type = ClassUtils.resolveClassName(interfaceName, resourceLoader.getClassLoader());
		AbstractBeanDefinition definition = BeanDefinitionBuilder
				.genericBeanDefinition(PlsqlPackageFactoryBean.class)
				.addConstructorArgValue(type)
				.addPropertyValue("dataSourceName", dataSourceName)
				.getBeanDefinition();
		// lets injection by type find the bean without creating it first
		definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, type);
		registry.registerBeanDefinition(interfaceName, definition);
	}
}
