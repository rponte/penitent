package io.github.rponte.penitent.mock.docs;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.rponte.penitent.mock.PlsqlMockExtension;
import io.github.rponte.penitent.mock.PlsqlMockScript;
import io.github.rponte.penitent.mock.PlsqlMocks;
import io.github.rponte.penitent.mock.TestDatabase;
import io.github.rponte.penitent.mock.TestKit;

/** The @PlsqlMockScript example of docs/testing.md: run by DocsTestingTest, through JUnit's test kit. */
@ExtendWith(TestKit.OnlyThroughTheTestKit.class)
class FreightItemsTest {

	static DataSource dataSource = PlsqlMocks.wrap(TestDatabase.lazyPool());

	@RegisterExtension
	static PlsqlMockExtension mocks = PlsqlMockExtension.on(() -> dataSource);

	JdbcTemplate jdbc = new JdbcTemplate(dataSource);

	// snippet: testing/script-annotation
	@Test
	@PlsqlMockScript("/mocks/freight-items-v.sql")
	void sees_the_items_of_the_script() {
	    Integer items = jdbc.queryForObject("SELECT COUNT(*) FROM freight_items_v WHERE uf = 'SP'", Integer.class);

	    assertThat(items).isEqualTo(2);
	}
	// end-snippet
}
