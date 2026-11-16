package io.github.rponte.penitent.mock.spring;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextAnnotationUtils;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;

import io.github.rponte.penitent.mock.PlsqlMockScript;
import io.github.rponte.penitent.mock.PlsqlMocks;
import io.github.rponte.penitent.mock.internal.AnnotatedScripts;

/**
 * Begins the test's edition before Spring's test transaction, and ends it after: ordered before
 * {@link TransactionalTestExecutionListener}, it runs first on the way in and last on the way out. The transaction
 * then takes its connection already in the edition, as Oracle requires, and gives it back before the edition is
 * dropped, as Oracle requires too. Once the edition begins, it applies the test's {@link PlsqlMockScript}s, the
 * class's first. Found by Spring Test through {@code META-INF/spring.factories}; it does nothing for a test class
 * without {@link EnablePlsqlMocks}, except refuse a {@link PlsqlMockScript} it would otherwise ignore. Not part of
 * the API.
 */
public final class PlsqlMocksTestExecutionListener extends AbstractTestExecutionListener {

	@Override
	public int getOrder() {
		return TransactionalTestExecutionListener.ORDER - 100;
	}

	@Override
	public void beforeTestMethod(TestContext context) {
		List<PlsqlMockScript> scripts = new ArrayList<>(
				TestContextAnnotationUtils.getMergedRepeatableAnnotations(context.getTestClass(), PlsqlMockScript.class));
		scripts.addAll(AnnotatedElementUtils.getMergedRepeatableAnnotations(context.getTestMethod(),
				PlsqlMockScript.class));
		String where = context.getTestClass().getSimpleName() + "." + context.getTestMethod().getName();
		if (!enabled(context)) {
			if (!scripts.isEmpty()) {
				throw new IllegalStateException("@PlsqlMockScript on " + where + " needs @EnablePlsqlMocks on the test"
						+ " class, to apply its scripts in the test's edition; without it, they would be ignored");
			}
			return;
		}
		PlsqlMocks mocks = mocksOf(context);
		mocks.begin();
		// the class's first, then the method's; a failure here still ends the mocks, in afterTestMethod
		AnnotatedScripts.apply(mocks, scripts, where);
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
