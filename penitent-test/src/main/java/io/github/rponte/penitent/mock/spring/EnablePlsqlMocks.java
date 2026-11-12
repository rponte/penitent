package io.github.rponte.penitent.mock.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import io.github.rponte.penitent.mock.PlsqlMocks;

/**
 * {@link PlsqlMocks} in a Spring test: the context's {@code DataSource} is wrapped, so every connection the
 * application takes enters the test's edition, and a {@link PlsqlMocks} bean, to inject, begins an edition before
 * each test, before Spring's test transaction, and ends it after the transaction.
 *
 * <pre>{@code
 * @SpringBootTest
 * @EnablePlsqlMocks
 * class FreightServiceTest {
 *
 *     @Autowired PlsqlMocks mocks;
 *
 *     @Test
 *     void calculates_freight() {
 *         when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p>Only the contexts of test classes with this annotation are changed, so Spring caches them apart from the others.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface EnablePlsqlMocks {

	/**
	 * The name of the {@code DataSource} bean the mocks use. Needed only when the context has several, and none of
	 * them is primary.
	 */
	String dataSource() default "";
}
