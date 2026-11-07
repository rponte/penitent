package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import io.github.rponte.penitent.PlsqlBlock;
import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;

/** The core's contract with its users: one connection per call, and exceptions only from the core itself. */
class ConnectionAndExceptionsTest extends OracleIntegrationTest {

	record Address(String street, Integer num) {
	}

	@PlsqlPackage("FIXTURE_COMPOSITES")
	interface Composites {
		Address echoAddress(Address value);

		List<Address> echoAddresses(List<Address> value);
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface WithError {
		void raiseError(String message);
	}

	@PlsqlPackage("FIXTURE_INVALID")
	interface Broken {
		int constant();
	}

	interface Block {
		@PlsqlBlock(value = "begin :result := ADDRESS_T('Street A', 1); end;", resultType = "ADDRESS_T")
		Address anAddress();
	}

	@Test
	@DisplayName("the core does not load Spring")
	void without_spring() {
		assertThatThrownBy(() -> Class.forName("org.springframework.jdbc.core.JdbcTemplate"))
				.isInstanceOf(ClassNotFoundException.class);
		assertThatThrownBy(() -> Class.forName("org.springframework.dao.DataAccessException"))
				.isInstanceOf(ClassNotFoundException.class);
	}

	@Test
	@DisplayName("the data dictionary is read on the call's connection: a pool of one connection is enough")
	void single_connection_pool() {
		try (HikariDataSource pool = newPool()) {
			pool.setMaximumPoolSize(1);
			pool.setConnectionTimeout(2_000);

			Composites composites = PlsqlPackages.create(Composites.class, pool);
			Address address = new Address("Street A", 1);

			assertThat(composites.echoAddress(address)).isEqualTo(address);
			assertThat(composites.echoAddresses(List.of(address))).containsExactly(address);
			assertThat(PlsqlPackages.create(Block.class, pool).anAddress()).isEqualTo(address);
		}
	}

	@Test
	@DisplayName("the connection goes back to the pool, even when the call fails")
	void returns_the_connection() {
		try (HikariDataSource pool = newPool()) {
			pool.setMaximumPoolSize(1);
			pool.setConnectionTimeout(2_000);
			WithError withError = PlsqlPackages.create(WithError.class, pool);

			for (int i = 0; i < 3; i++) {
				assertThatThrownBy(() -> withError.raiseError("rule violated"))
						.isInstanceOf(PlsqlExecutionException.class);
			}
		}
	}

	@Test
	@DisplayName("without a translator, RAISE_APPLICATION_ERROR carries the code and the reason, and the Oracle message intact")
	void business_exception() {
		assertThatThrownBy(() -> pkg(WithError.class).raiseError("rule violated"))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class, e -> {
					assertThat(e.getCode()).isEqualTo(20100);
					assertThat(e.getReason()).isEqualTo("rule violated");
					assertThat(e.getMessage()).contains("ORA-20100: rule violated").contains("ORA-06512");
					assertThat(e.getSql()).contains("FIXTURE_SCALARS.RAISE_ERROR");
					assertThat(e.getCause()).isInstanceOf(SQLException.class)
							.hasMessageContaining("ORA-20100: rule violated")
							.hasMessageContaining("ORA-06512");
				});
	}

	@Test
	@DisplayName("the reason can span several lines, and only the Oracle stack is left out")
	void multiline_message() {
		String message = "first line\nsecond: with a colon";

		assertThatThrownBy(() -> pkg(WithError.class).raiseError(message))
				.isInstanceOfSatisfying(PlsqlRoutineRaisedException.class,
						e -> assertThat(e.getReason()).isEqualTo(message));
	}

	@Test
	@DisplayName("any other database failure is still a PlsqlExecutionException, with the ORA in the message")
	void failure_that_was_not_raised() {
		assertThatThrownBy(() -> pkg(Broken.class).constant())
				.isInstanceOf(PlsqlExecutionException.class)
				.isNotInstanceOf(PlsqlRoutineRaisedException.class)
				.hasMessageContaining("ORA-04063");
	}

	@Test
	@DisplayName("the translator decides the exception, and receives the statement and the cause")
	void translator() {
		AtomicReference<String> command = new AtomicReference<>();
		WithError withError = PlsqlPackages.create(WithError.class, DATA_SOURCE, fails -> {
			command.set(fails.sql());
			return new IllegalStateException(fails.cause().getErrorCode() + "");
		});

		assertThatThrownBy(() -> withError.raiseError("rule violated"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("20100");
		assertThat(command.get()).contains("FIXTURE_SCALARS.RAISE_ERROR");
	}

	@Test
	@DisplayName("a failure to obtain the connection also goes through the translator")
	void failure_to_connect() {
		DataSource withoutConnection = (DataSource) java.lang.reflect.Proxy.newProxyInstance(
				DataSource.class.getClassLoader(), new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					throw new SQLException("no connection");
				});

		assertThatThrownBy(() -> PlsqlPackages.create(WithError.class, withoutConnection).raiseError("x"))
				.isInstanceOf(PlsqlExecutionException.class)
				.hasMessage("no connection");
	}
}
