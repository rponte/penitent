package io.github.rponte.penitent.mock.docs;

import static io.github.rponte.penitent.mock.PlsqlMocks.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import io.github.rponte.penitent.mock.PlsqlMocks;
import io.github.rponte.penitent.mock.TestDatabase;

/**
 * Architecture decision 0001 shows the PL/SQL the mocks generate for the first mock of the Testing page: this test
 * generates it, through the log of the mocks, and checks that the decision shows it as it is.
 */
class DocsAdrTest {

	private static final Path ADR = Path.of("..", "docs", "adr", "0001-mocking-plsql-with-editions.md");

	@Test
	@DisplayName("ADR 0001 shows the PL/SQL generated for the first mock, as the mocks generate it")
	void generated_code() throws IOException {
		Logger log = (Logger) LoggerFactory.getLogger("io.github.rponte.penitent.mock");
		Level configured = log.getLevel();
		ListAppender<ILoggingEvent> events = new ListAppender<>();
		events.start();
		log.addAppender(events);
		log.setLevel(Level.TRACE);
		DataSource dataSource = PlsqlMocks.wrap(TestDatabase.pool());
		try (PlsqlMocks mocks = PlsqlMocks.on(dataSource)) {
			mocks.begin();
			Freights freights = mocks.mock(Freights.class);
			when(freights.calculate("SP", "CE")).thenReturn(new BigDecimal("24.90"));
		} finally {
			log.detachAppender(events);
			log.setLevel(configured);
		}

		String generated = events.list.stream().map(ILoggingEvent::getFormattedMessage)
				.filter(line -> line.startsWith("-> generated PL/SQL"))
				.findFirst().orElseThrow()
				.replaceFirst("^-> generated PL/SQL : ", "")
				.replace("\n" + " ".repeat(22), "\n");

		assertThat(Files.readString(ADR)).as("the generated PL/SQL, in a block of ADR 0001")
				.contains("```sql\n" + generated + "\n```");
	}
}
