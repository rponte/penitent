package io.github.rponte.penitent.mock.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import io.github.rponte.penitent.mock.TestKit;

/** The test without mocks of docs/testing.md: run by DocsTestingTest, through JUnit's test kit. */
@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
// snippet: testing/arrange-act-assert
@SpringBootTest
@Transactional
class FreightReportTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired Freights freights;

    @Test
    void counts_the_items_of_a_state() {
        // arrange
        jdbc.update("INSERT INTO FREIGHT_ITEM (ID, UF) VALUES (7001, 'CE')");
        jdbc.update("INSERT INTO FREIGHT_ITEM (ID, UF) VALUES (7002, 'CE')");

        // act
        int items = freights.countItems("CE");

        // assert
        assertThat(items).isEqualTo(2);
    }
}
// end-snippet
