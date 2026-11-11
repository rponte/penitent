package io.github.rponte.penitent.spring;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Device;

/**
 * The Oracle the tests run against, in a container, with the user {@value #USER}: Oracle 23ai by default,
 * or another image given by the {@code oracle.database.image} property.
 *
 * <ul>
 * <li>{@code gvenzl/oracle-free} and {@code gvenzl/oracle-xe}, as {@code gvenzl/oracle-xe:21-slim-faststart} for
 * Oracle 21c, through the Testcontainers module, which creates the user;
 * <li>{@code ghcr.io/oracle/adb-free}, Oracle's Autonomous Database Free, whose tag {@code latest} is Oracle 19c,
 * the only 19c image that Oracle publishes without a login. It has no user but {@code ADMIN}, so this class
 * creates {@value #USER} with the grants that gvenzl's Oracle XE gives it.
 * </ul>
 *
 * <p>A copy of penitent-core's {@code OracleUnderTest}: a test class cannot be shared between modules without
 * publishing a test jar.
 *
 * <p>Every image is reached the same way: plain TCP, a service name, an administrator for what a regular user
 * cannot do, and the default tablespace read from the database, {@code USERS} or, on the Autonomous Database,
 * {@code DATA}.
 */
abstract class OracleUnderTest {

	static final String USER = "plsql_test";

	/** Long, mixed and without a user's name, as the Autonomous Database requires of every password. */
	static final String PASSWORD = "Penitent_Test_2026";

	abstract String jdbcUrl();

	abstract String adminUser();

	static OracleUnderTest start(String image) {
		OracleUnderTest oracle = image.contains("adb-free") ? new Autonomous(image) : new Gvenzl(image);
		oracle.startContainer();
		return oracle;
	}

	abstract void startContainer();

	/** The tablespace a new user's tables go to, for its quota. */
	String defaultTablespace() {
		try (Connection connection = admin();
				Statement statement = connection.createStatement();
				var result = statement.executeQuery("SELECT property_value FROM database_properties"
						+ " WHERE property_name = 'DEFAULT_PERMANENT_TABLESPACE'")) {
			result.next();
			return result.getString(1);
		} catch (SQLException e) {
			throw new IllegalStateException("failed to read the default tablespace of the test database", e);
		}
	}

	void executeAsAdmin(String... commands) {
		try (Connection connection = admin(); Statement statement = connection.createStatement()) {
			for (String command : commands) {
				statement.execute(command);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to prepare the test database", e);
		}
	}

	private Connection admin() throws SQLException {
		return DriverManager.getConnection(jdbcUrl(), adminUser(), PASSWORD);
	}

	private static final class Gvenzl extends OracleUnderTest {

		private final OracleContainer container;

		Gvenzl(String image) {
			container = new OracleContainer(DockerImageName.parse(image).asCompatibleSubstituteFor("gvenzl/oracle-free")) {
				// the Testcontainers module is for Oracle Free, whose database is FREEPDB1; Oracle XE, as 21c, calls it XEPDB1
				@Override
				public String getDatabaseName() {
					return image.contains("oracle-xe") ? "XEPDB1" : super.getDatabaseName();
				}
			}
					.withUsername(USER)
					.withPassword(PASSWORD);
		}

		@Override
		void startContainer() {
			// no stop: Testcontainers tears the container down when the JVM exits
			container.start();
		}

		@Override
		String jdbcUrl() {
			return container.getJdbcUrl();
		}

		@Override
		String adminUser() {
			return "system";
		}
	}

	/**
	 * Oracle's Autonomous Database Free. Its listener takes plain TCP on 1521, besides TLS on 1522, which its
	 * documentation is about: no wallet is needed. It mounts a file system of its own, hence {@code SYS_ADMIN} and
	 * {@code /dev/fuse}, and it reports itself ready through the container's health check. The image is for
	 * {@code linux/amd64} only; on an ARM machine, Docker runs it emulated.
	 */
	private static final class Autonomous extends OracleUnderTest {

		private static final int PORT = 1521;
		private static final String SERVICE = "myatp_tp.adb.oraclecloud.com";

		private final GenericContainer<?> container;

		@SuppressWarnings("resource") // no stop: Testcontainers tears the container down when the JVM exits
		Autonomous(String image) {
			container = new GenericContainer<>(DockerImageName.parse(image))
					.withEnv("WORKLOAD_TYPE", "ATP")
					.withEnv("ADMIN_PASSWORD", PASSWORD)
					.withEnv("WALLET_PASSWORD", PASSWORD)
					.withEnv("ENABLE_ARCHIVE_LOG", "False")
					.withCreateContainerCmdModifier(command -> command.getHostConfig()
							.withCapAdd(Capability.SYS_ADMIN)
							.withDevices(Device.parse("/dev/fuse")))
					.withExposedPorts(PORT)
					.waitingFor(Wait.forHealthcheck().withStartupTimeout(Duration.ofMinutes(10)));
		}

		@Override
		void startContainer() {
			container.start();
			awaitAdminPassword();
			executeAsAdmin(
					"CREATE USER " + USER + " IDENTIFIED BY \"" + PASSWORD + "\" QUOTA UNLIMITED ON " + defaultTablespace(),
					"GRANT CONNECT, RESOURCE, CREATE VIEW, CREATE MATERIALIZED VIEW, CREATE SYNONYM TO " + USER);
		}

		/**
		 * The health check turns healthy before the container sets {@code ADMIN}'s password, which until then
		 * refuses the login with ORA-01017: the login is retried for a while.
		 */
		private void awaitAdminPassword() {
			long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
			while (true) {
				try (Connection connection = DriverManager.getConnection(jdbcUrl(), adminUser(), PASSWORD)) {
					return;
				} catch (SQLException e) {
					if (System.nanoTime() > deadline) {
						throw new IllegalStateException("the test database did not accept the administrator's login", e);
					}
				}
				try {
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("interrupted while waiting for the test database", e);
				}
			}
		}

		@Override
		String jdbcUrl() {
			return "jdbc:oracle:thin:@//" + container.getHost() + ":" + container.getMappedPort(PORT) + "/" + SERVICE;
		}

		@Override
		String adminUser() {
			return "admin";
		}
	}
}
