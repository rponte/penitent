package io.github.rponte.penitent.mock;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import io.github.rponte.penitent.mock.DocsTestingTest.Application;
import io.github.rponte.penitent.mock.DocsTestingTest.Freights;
import io.github.rponte.penitent.mock.spring.EnablePlsqlMocks;

/** The Spring example of docs/testing.md: run by DocsTestingTest, through JUnit's test kit. */
@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
// snippet: testing/spring
@SpringJUnitConfig(Application.class)   // or @SpringBootTest
@EnablePlsqlMocks
@Transactional
class FreightServiceTest {

    @Autowired PlsqlMocks mocks;
    @Autowired Freights freights;

    @Test
    void calculates_freight() {
        when(mocks.mock(Freights.class).calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));

        assertThat(freights.calculate("SP", "CE")).isEqualByComparingTo("24.90");
    }
}
// end-snippet
