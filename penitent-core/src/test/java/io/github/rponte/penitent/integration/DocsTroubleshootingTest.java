package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.PlsqlSchema;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * The messages of docs/troubleshooting.md, as the page quotes them. A change here changes the page,
 * and the other way around.
 */
class DocsTroubleshootingTest extends OracleIntegrationTest {

	@PlsqlPackage("ORDER_PKG")
	interface MisspelledPackage {
		String orderStatus(long orderId);
	}

	@PlsqlSchema
	interface Money {
		long roundToCents(BigDecimal value);
	}

	@Test
	@DisplayName("a method that does not match the routine, one call at a time")
	void method_mismatches() {
		DocsGettingStartedTest.Broken.Orders orders = PlsqlPackages.create(DocsGettingStartedTest.Broken.Orders.class,
				DATA_SOURCE);

		assertThatThrownBy(() -> orders.cancelOrder(1))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), getClass()),
						"troubleshooting.md#procedure-as-function"));

		assertThatThrownBy(() -> orders.placeOrder(1, BigDecimal.ONE, 1L))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), getClass()),
						"troubleshooting.md#out-without-plsqlout"));
	}

	@Test
	@DisplayName("a package that does not exist, or that the session cannot see")
	void missing_package() {
		MisspelledPackage orders = PlsqlPackages.create(MisspelledPackage.class, DATA_SOURCE);

		assertThatThrownBy(() -> orders.orderStatus(1))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), getClass()),
						"troubleshooting.md#package-not-found"));
	}

	@Test
	@DisplayName("NULL read into a primitive")
	void null_into_primitive() {
		Money money = PlsqlPackages.create(Money.class, DATA_SOURCE);

		assertThatThrownBy(() -> money.roundToCents(null))
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(Docs.asDocumented(e.getMessage(), getClass()),
						"troubleshooting.md#null-primitive"));
	}

	@Test
	@DisplayName("a block compiled without -parameters says so, instead of binding arg0")
	void without_parameters_flag(@TempDir Path dir) throws Exception {
		Class<?> blocks = compileWithoutParametersFlag(dir, "docs.Blocks", """
				package docs;

				public interface Blocks {
				    @io.github.rponte.penitent.PlsqlBlock("begin :result := upper(:text); end;")
				    String upper(String text);
				}
				""");
		Object proxy = PlsqlPackages.create(blocks, DATA_SOURCE);

		assertThatThrownBy(() -> blocks.getMethod("upper", String.class).invoke(proxy, "x"))
				.cause()
				.isInstanceOf(PlsqlMappingException.class)
				.satisfies(e -> Docs.assertPagesShow(e.getMessage(), "troubleshooting.md#parameters-flag"));
	}

	private static Class<?> compileWithoutParametersFlag(Path dir, String name, String source)
			throws IOException, ClassNotFoundException {
		Path file = dir.resolve(name.replace('.', '/') + ".java");
		Files.createDirectories(file.getParent());
		Files.writeString(file, source, StandardCharsets.UTF_8);

		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		int status = compiler.run(null, null, null, List.of("-d", dir.toString(), "-classpath",
				System.getProperty("java.class.path"), file.toString()).toArray(String[]::new));
		if (status != 0) {
			throw new IllegalStateException("the test interface did not compile");
		}
		URLClassLoader loader = new URLClassLoader(new URL[] { dir.toUri().toURL() },
				DocsTroubleshootingTest.class.getClassLoader());
		return loader.loadClass(name);
	}
}
