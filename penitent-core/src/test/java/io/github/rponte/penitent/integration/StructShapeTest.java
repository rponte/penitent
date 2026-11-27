package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.Struct;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlExecutionException;

/**
 * A {@code Struct} that comes back with another number of attributes than the proxy described its type with (#183): as
 * after the type changed since, or as the database or the driver hands back a type's old shape, after its table or the
 * type itself changed while the application ran (#184, B1). It is refused, saying what happened, instead of read by the
 * binding's positions: past its end, a raw {@code ArrayIndexOutOfBoundsException}; short of it, values in the wrong
 * properties.
 */
class StructShapeTest extends OracleIntegrationTest {

	private static final ObjectTypesTest.Address ADDRESS = new ObjectTypesTest.Address("Street A", 1);

	private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
		try {
			return method.invoke(target, arguments);
		} catch (InvocationTargetException e) {
			throw e.getCause();
		}
	}

	/** {@code real}, as a {@code Struct} whose attributes are as many as {@code count} says, or as they are. */
	private static Struct reshaped(Struct real, int count) {
		return (Struct) Proxy.newProxyInstance(StructShapeTest.class.getClassLoader(), new Class<?>[] { Struct.class },
				(p, m, a) -> m.getName().equals("getAttributes")
						? Arrays.copyOf((Object[]) invoke(real, m, a), count)
						: invoke(real, m, a));
	}

	/** A DataSource whose calls hand back the {@code Struct}s with {@code count} attributes, while it is not 0. */
	private static DataSource reshaping(AtomicInteger count) {
		return (DataSource) Proxy.newProxyInstance(StructShapeTest.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (p, m, a) -> {
					Object result = invoke(DATA_SOURCE, m, a);
					if (!(result instanceof Connection connection)) {
						return result;
					}
					return Proxy.newProxyInstance(StructShapeTest.class.getClassLoader(),
							new Class<?>[] { Connection.class }, (cp, cm, ca) -> {
								Object prepared = invoke(connection, cm, ca);
								if (!(prepared instanceof CallableStatement statement)) {
									return prepared;
								}
								return Proxy.newProxyInstance(StructShapeTest.class.getClassLoader(),
										new Class<?>[] { CallableStatement.class }, (sp, sm, sa) -> {
											Object value = invoke(statement, sm, sa);
											return sm.getName().equals("getObject") && value instanceof Struct struct
													&& count.get() > 0 ? reshaped(struct, count.get()) : value;
										});
							});
				});
	}

	@Test
	@DisplayName("a Struct with fewer attributes than its type: refused, saying so, not an ArrayIndexOutOfBoundsException")
	void fewer_attributes() {
		AtomicInteger count = new AtomicInteger(1);
		ObjectTypesTest.Composites composites = PlsqlPackages.create(ObjectTypesTest.Composites.class, reshaping(count));

		assertThatThrownBy(() -> composites.echoAddress(ADDRESS)).isInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("ADDRESS_T came back with 1 attribute")
				.hasMessageContaining("described it with 2: [STREET, NUM]");
	}

	@Test
	@DisplayName("a Struct with more attributes than its type: refused, not read into the wrong properties")
	void more_attributes() {
		AtomicInteger count = new AtomicInteger(3);
		ObjectTypesTest.Composites composites = PlsqlPackages.create(ObjectTypesTest.Composites.class, reshaping(count));

		assertThatThrownBy(() -> composites.echoAddress(ADDRESS)).isInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("ADDRESS_T came back with 3 attributes")
				.hasMessageContaining("described it with 2: [STREET, NUM]");
	}

	interface Blocks {
		@io.github.rponte.penitent.PlsqlBlock(value = "begin :result := address_t('Street A', 1); end;",
				resultType = "ADDRESS_T")
		ObjectTypesTest.Address address();
	}

	@Test
	@DisplayName("a block's Struct of another shape: refused the same, and the next call reads the types again")
	void block() {
		AtomicInteger count = new AtomicInteger(1);
		Blocks blocks = PlsqlPackages.create(Blocks.class, reshaping(count));

		assertThatThrownBy(blocks::address).isInstanceOf(PlsqlExecutionException.class)
				.hasMessageContaining("ADDRESS_T came back with 1 attribute");
		count.set(0);
		assertThat(blocks.address()).isEqualTo(ADDRESS);
	}

	record Grown(java.math.BigDecimal a, java.math.BigDecimal b, java.math.BigDecimal c) {
	}

	@io.github.rponte.penitent.PlsqlPackage("SHAPE_PKG")
	interface Shapes {
		Grown make();
	}

	private static void ddl(String... statements) {
		try (Connection c = DATA_SOURCE.getConnection(); java.sql.Statement s = c.createStatement()) {
			for (String sql : statements) {
				s.execute(sql);
			}
		} catch (java.sql.SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("a type that gained an attribute, read on a new connection: refused once, then read with its new shape")
	void type_gained_an_attribute() {
		ddl("BEGIN EXECUTE IMMEDIATE 'DROP PACKAGE shape_pkg'; EXCEPTION WHEN OTHERS THEN NULL; END;",
				"BEGIN EXECUTE IMMEDIATE 'DROP TYPE shape_t FORCE'; EXCEPTION WHEN OTHERS THEN NULL; END;",
				"CREATE TYPE shape_t AS OBJECT (a NUMBER, b NUMBER)",
				"CREATE PACKAGE shape_pkg AS FUNCTION make RETURN shape_t; END;",
				"CREATE PACKAGE BODY shape_pkg AS FUNCTION make RETURN shape_t IS BEGIN RETURN shape_t(1, 2); END; END;");
		com.zaxxer.hikari.HikariConfig config = new com.zaxxer.hikari.HikariConfig();
		try (com.zaxxer.hikari.HikariDataSource template = newPool()) {
			config.setJdbcUrl(template.getJdbcUrl());
			config.setUsername(template.getUsername());
			config.setPassword(template.getPassword());
		}
		config.setMaximumPoolSize(1);
		try (com.zaxxer.hikari.HikariDataSource pool = new com.zaxxer.hikari.HikariDataSource(config)) {
			Shapes shapes = PlsqlPackages.create(Shapes.class, pool);
			assertThat(shapes.make()).isEqualTo(new Grown(java.math.BigDecimal.ONE, java.math.BigDecimal.valueOf(2), null));

			ddl("ALTER TYPE shape_t ADD ATTRIBUTE (c NUMBER) CASCADE",
					"CREATE OR REPLACE PACKAGE BODY shape_pkg AS FUNCTION make RETURN shape_t IS"
							+ " BEGIN RETURN shape_t(1, 2, 3); END; END;");
			// a new connection, which reads the type as it is now; the proxy's binding still has two attributes
			pool.getHikariPoolMXBean().softEvictConnections();

			assertThatThrownBy(shapes::make).isInstanceOf(PlsqlExecutionException.class)
					.hasMessageContaining("SHAPE_T came back with 3 attributes")
					.hasMessageContaining("described it with 2: [A, B]");
			assertThat(shapes.make()).isEqualTo(new Grown(java.math.BigDecimal.ONE, java.math.BigDecimal.valueOf(2),
					java.math.BigDecimal.valueOf(3)));
		} finally {
			ddl("DROP PACKAGE shape_pkg", "DROP TYPE shape_t FORCE");
		}
	}

	@Test
	@DisplayName("after one, the proxy reads its types again, and a Struct of the type's shape is read")
	void the_next_call_reads_again() {
		AtomicInteger count = new AtomicInteger(1);
		ObjectTypesTest.Composites composites = PlsqlPackages.create(ObjectTypesTest.Composites.class, reshaping(count));
		assertThatThrownBy(() -> composites.echoAddress(ADDRESS)).isInstanceOf(PlsqlExecutionException.class);

		count.set(0);
		assertThat(composites.echoAddress(ADDRESS)).isEqualTo(ADDRESS);
	}
}
