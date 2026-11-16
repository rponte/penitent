package io.github.rponte.penitent.mock;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;

import io.github.rponte.penitent.mock.internal.AnnotatedScripts;

/**
 * {@link PlsqlMocks} for JUnit 5: each test gets an edition of its own, created before its {@code @BeforeEach}
 * methods and dropped after its {@code @AfterEach} ones, whether the test passes or fails.
 *
 * <pre>{@code
 * @RegisterExtension
 * static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);   // from PlsqlMocks.wrap
 *
 * @Test
 * void calculates_freight() {
 *     Freights freights = mocks.mock(Freights.class);
 *     when(freights.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));
 *     // ...
 * }
 * }</pre>
 *
 * <p>A test method may also take the test's {@link PlsqlMocks} as a parameter. Tests that mock run one at a time:
 * an edition can have only one child, so two tests cannot each have one at the same time.
 */
public final class PlsqlMockExtension implements BeforeEachCallback, AfterEachCallback, ParameterResolver {

	private final Supplier<DataSource> dataSource;
	private volatile PlsqlMocks current;

	private PlsqlMockExtension(Supplier<DataSource> dataSource) {
		this.dataSource = dataSource;
	}

	/** The extension, for the wrapped {@code DataSource} that {@code dataSource} supplies when the first test begins. */
	public static PlsqlMockExtension on(Supplier<DataSource> dataSource) {
		return new PlsqlMockExtension(Objects.requireNonNull(dataSource, "dataSource"));
	}

	/** A mock of {@code type}, for the test running: see {@link PlsqlMocks#mock}. */
	public <T> T mock(Class<T> type) {
		return running().mock(type);
	}

	@Override
	public void beforeEach(ExtensionContext context) {
		if (current != null) {
			throw new IllegalStateException("another test is still mocking: tests that mock run one at a time, since an"
					+ " edition can have only one child");
		}
		PlsqlMocks mocks = PlsqlMocks.on(dataSource.get());
		mocks.begin();
		current = mocks;
		// the classes' first, the outermost first, then the method's; a failure here still ends the mocks, in afterEach
		List<PlsqlMockScript> scripts = new ArrayList<>();
		for (Class<?> type : enclosingFirst(context.getRequiredTestClass())) {
			scripts.addAll(AnnotationSupport.findRepeatableAnnotations(type, PlsqlMockScript.class));
		}
		scripts.addAll(AnnotationSupport.findRepeatableAnnotations(context.getRequiredTestMethod(), PlsqlMockScript.class));
		AnnotatedScripts.apply(mocks, scripts, context.getRequiredTestClass().getSimpleName() + "."
				+ context.getRequiredTestMethod().getName());
	}

	/**
	 * The test class and, for a {@code @Nested} one, the classes that enclose it, the outermost first: JUnit finds the
	 * annotations of a class and its superclasses, not of the classes around it, which Spring does.
	 */
	private static List<Class<?>> enclosingFirst(Class<?> testClass) {
		List<Class<?>> classes = new ArrayList<>();
		for (Class<?> type = testClass; type != null; type = Modifier.isStatic(type.getModifiers()) ? null
				: type.getEnclosingClass()) {
			classes.add(0, type);
		}
		return classes;
	}

	@Override
	public void afterEach(ExtensionContext context) {
		PlsqlMocks mocks = current;
		current = null;
		if (mocks != null) {
			mocks.end();
		}
	}

	@Override
	public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
		return parameter.getParameter().getType() == PlsqlMocks.class;
	}

	@Override
	public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
		return running();
	}

	private PlsqlMocks running() {
		PlsqlMocks mocks = current;
		if (mocks == null) {
			throw new IllegalStateException("no test is running with this extension: mock(...) works inside a test, or"
					+ " in its @BeforeEach, with the extension registered on the test class");
		}
		return mocks;
	}
}
