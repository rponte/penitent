package io.github.rponte.penitent.mock.spring;

import java.util.List;

import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.TestContextAnnotationUtils;

/**
 * Changes the context of a test class with {@link EnablePlsqlMocks}, and only of those: found by Spring Test through
 * {@code META-INF/spring.factories}. Not part of the API.
 */
public final class PlsqlMocksContextCustomizerFactory implements ContextCustomizerFactory {

	@Override
	public ContextCustomizer createContextCustomizer(Class<?> testClass,
			List<ContextConfigurationAttributes> configAttributes) {
		EnablePlsqlMocks enabled = TestContextAnnotationUtils.findMergedAnnotation(testClass, EnablePlsqlMocks.class);
		return enabled == null ? null : new PlsqlMocksContextCustomizer(enabled.dataSource());
	}
}
