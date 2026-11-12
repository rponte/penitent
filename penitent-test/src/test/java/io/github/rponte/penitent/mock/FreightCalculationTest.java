package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.mock.DocsTestingTest.Freights;

/** The JUnit 5 example of docs/testing.md: run by DocsTestingTest, through JUnit's test kit. */
@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
class FreightCalculationTest {

	static final javax.sql.DataSource dataSource = DocsTestingTest.dataSource;

	final Freights freights = PlsqlPackages.create(Freights.class, dataSource);

	// snippet: testing/junit
	@RegisterExtension
	static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);   // from PlsqlMocks.wrap

	@Test
	void calculates_freight() {
	    Freights mock = mocks.mock(Freights.class);
	    when(mock.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

	    assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
	}
	// end-snippet
}
