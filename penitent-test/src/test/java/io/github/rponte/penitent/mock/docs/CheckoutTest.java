package io.github.rponte.penitent.mock.docs;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.mock.PlsqlMockExtension;
import io.github.rponte.penitent.mock.PlsqlMocks;
import io.github.rponte.penitent.mock.TestDatabase;
import io.github.rponte.penitent.mock.TestKit;

/** The JUnit 5 example of docs/testing.md: run by DocsTestingTest, through JUnit's test kit. */
@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
class CheckoutTest {

	static DataSource pool = TestDatabase.pool();

	// snippet: testing/junit
	// the DataSource the application uses in tests, wrapped
	static DataSource dataSource = PlsqlMocks.wrap(pool);

	@RegisterExtension
	static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

	CheckoutService checkout = new CheckoutService(PlsqlPackages.create(Freights.class, dataSource));

	@Test
	void adds_the_freight_to_the_subtotal() {
	    // arrange
	    Freights freights = mocks.mock(Freights.class);
	    when(freights.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

	    // act
	    BigDecimal total = checkout.total(new BigDecimal("100.00"), "SP", "CE");

	    // assert
	    assertThat(total).isEqualByComparingTo("124.90");
	}
	// end-snippet
}
