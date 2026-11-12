package io.github.rponte.penitent.mock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import io.github.rponte.penitent.mock.DocsTestingTest.Application;
import io.github.rponte.penitent.mock.DocsTestingTest.Freights;

/** The test without mocks of docs/testing.md: run by DocsTestingTest, through JUnit's test kit. */
@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
// snippet: testing/arrange-act-assert
@SpringJUnitConfig(Application.class)   // or @SpringBootTest
@Transactional                          // each test is rolled back at its end
class FreightReportTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired Freights freights;

    @Test
    void counts_the_items_of_a_state() {
        jdbc.update("INSERT INTO FREIGHT_ITEM (ID, UF) VALUES (7001, 'CE')");   // arrange
        jdbc.update("INSERT INTO FREIGHT_ITEM (ID, UF) VALUES (7002, 'CE')");

        int items = freights.countItems("CE");                                    // act

        assertThat(items).isEqualTo(2);                                           // assert
    }
}
// end-snippet
