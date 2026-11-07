package io.github.rponte.penitent.boot;

import java.util.Arrays;

import javax.sql.DataSource;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.MethodMetadata;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.spring.EnablePlsqlPackages;
import io.github.rponte.penitent.spring.PlsqlPackagesValidator;

/**
 * Registers the interfaces of the application's packages as beans, as {@link EnablePlsqlPackages}
 * would, and builds the {@link PlsqlSettings} from the {@code penitent.*} properties.
 *
 * <ul>
 * <li>It needs a single {@code DataSource}, or a primary one. With several, declare
 * {@code @EnablePlsqlPackages(dataSource = "...")}.
 * <li>A {@code PlsqlSettings} bean of the application wins, and then the {@code penitent.*} settings
 * properties do not apply; a warning at startup says which ones are set. To keep them and add
 * converters or a translator, declare a {@link PlsqlSettingsCustomizer} instead.
 * <li>{@code penitent.validation.enabled} checks every registered interface against the database at
 * startup, whoever registered it.
 * </ul>
 */
// names the DataSource autoconfiguration instead of its class: in Spring Boot 4 it lives in its own
// module, which the starter does not need
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass({ PlsqlPackages.class, DataSource.class })
@ConditionalOnSingleCandidate(DataSource.class)
@EnableConfigurationProperties(PlsqlProperties.class)
@Import(PlsqlPackagesAutoRegistrar.class)
public class PlsqlAutoConfiguration implements SmartInitializingSingleton, BeanFactoryAware {

	private static final Log LOG = LogFactory.getLog(PlsqlAutoConfiguration.class);

	private final PlsqlProperties properties;
	private ConfigurableListableBeanFactory beanFactory;

	public PlsqlAutoConfiguration(PlsqlProperties properties) {
		this.properties = properties;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
	}

	@Bean
	@ConditionalOnMissingBean
	PlsqlSettings plsqlSettings(ObjectProvider<PlsqlSettingsCustomizer> customizers) {
		PlsqlSettings settings = PlsqlSettings.defaults();
		if (properties.getBind() != null) {
			settings = settings.withBind(properties.getBind());
		}
		for (PlsqlSettingsCustomizer customizer : customizers.orderedStream().toList()) {
			settings = java.util.Objects.requireNonNull(customizer.customize(settings),
					() -> customizer + " returned null; return the settings to use");
		}
		return settings;
	}

	@Bean
	@ConditionalOnBooleanProperty("penitent.validation.enabled")
	static PlsqlPackagesValidator plsqlPackagesValidator() {
		return PlsqlPackagesValidator.all();
	}

	/**
	 * Spring Boot ignores the properties of a bean the application replaced, without a word. Here a
	 * warning says so, because a {@code penitent.bind} that does not apply changes how values are bound.
	 * It reads the bean definitions, not the beans, so it holds with lazy initialization.
	 */
	@Override
	public void afterSingletonsInstantiated() {
		if (properties.getBind() == null || settingsFromStarter()) {
			return;
		}
		LOG.warn("penitent.bind is set but not applied: a PlsqlSettings bean is defined, and it wins. Set the bind"
				+ " in that bean, or replace it with a PlsqlSettingsCustomizer to keep the penitent.* properties.");
	}

	private boolean settingsFromStarter() {
		return Arrays.stream(beanFactory.getBeanNamesForType(PlsqlSettings.class, true, false))
				.map(beanFactory::getBeanDefinition)
				.anyMatch(definition -> definition instanceof AnnotatedBeanDefinition annotated
						&& factoryMethodOfThisClass(annotated.getFactoryMethodMetadata()));
	}

	private static boolean factoryMethodOfThisClass(MethodMetadata method) {
		return method != null && PlsqlAutoConfiguration.class.getName().equals(method.getDeclaringClassName());
	}
}
