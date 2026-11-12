package io.github.rponte.penitent.mock.docs;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.github.rponte.penitent.mock.PlsqlMocks;
import io.github.rponte.penitent.mock.TestKit;
import io.github.rponte.penitent.mock.spring.EnablePlsqlMocks;

/** The first mock of docs/testing.md: run by DocsTestingTest, through JUnit's test kit. */
@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
// snippet: testing/spring
@SpringBootTest
@EnablePlsqlMocks
class CheckoutServiceTest {

    @Autowired PlsqlMocks mocks;
    @Autowired CheckoutService checkout;

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
}
// end-snippet
