package io.github.rponte.penitent.mock.spring;

import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;

import io.github.rponte.penitent.mock.PlsqlMocks;

/**
 * Begins the test's edition before Spring's test transaction, and ends it after: ordered before
 * {@link TransactionalTestExecutionListener}, it runs first on the way in and last on the way out. The transaction
 * then takes its connection already in the edition, as Oracle requires, and gives it back before the edition is
 * dropped, as Oracle requires too. Found by Spring Test through {@code META-INF/spring.factories}; it does nothing
 * for a test class without {@link EnablePlsqlMocks}. Not part of the API.
 */
public final class PlsqlMocksTestExecutionListener extends AbstractTestExecutionListener {

	@Override
	public int getOrder() {
		return TransactionalTestExecutionListener.ORDER - 100;
	}

	@Override
	public void beforeTestMethod(TestContext context) {
		if (enabled(context)) {
			mocksOf(context).begin();
		}
	}

	@Override
	public void afterTestMethod(TestContext context) {
		if (enabled(context)) {
			mocksOf(context).end();
		}
	}

	private static boolean enabled(TestContext context) {
		return TestContextAnnotationUtils.findMergedAnnotation(context.getTestClass(), EnablePlsqlMocks.class) != null;
	}

	private static PlsqlMocks mocksOf(TestContext context) {
		return context.getApplicationContext().getBean(PlsqlMocksContextCustomizer.MOCKS, PlsqlMocks.class);
	}
}
