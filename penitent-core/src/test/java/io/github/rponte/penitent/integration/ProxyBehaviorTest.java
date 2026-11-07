package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.PlsqlPackage;

/** {@code public}, with a public and a non-public interface: a {@code default} method has to work in both. */
public class ProxyBehaviorTest extends OracleIntegrationTest {

	@PlsqlPackage("FIXTURE_SCALARS")
	public interface Public {
		int constant();

		default int constantPlusOne() {
			return constant() + 1;
		}
	}

	@PlsqlPackage("FIXTURE_SCALARS")
	interface NotPublic {
		int constant();

		default int constantPlusOne() {
			return constant() + 1;
		}

		default int constantPlus(int value) {
			return constant() + value;
		}

		default int failing() {
			throw new IllegalStateException("thrown by the default method");
		}
	}

	@Test
	@DisplayName("concurrent calls on the same proxy")
	void concurrency() throws InterruptedException, ExecutionException {
		Public proxy = pkg(Public.class);
		ExecutorService executor = Executors.newFixedThreadPool(4);
		try {
			List<Future<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < 40; i++) {
				calls.add(executor.submit(proxy::constant));
			}
			for (Future<Integer> call : calls) {
				assertThat(call.get()).isEqualTo(42);
			}
		} finally {
			executor.shutdown();
		}
	}

	@Test
	@DisplayName("default method in a public interface")
	void default_in_public_interface() {
		assertThat(pkg(Public.class).constantPlusOne()).isEqualTo(43);
	}

	@Test
	@DisplayName("toString and hashCode do not go to the database")
	void to_string_and_hash_code() {
		Public proxy = pkg(Public.class);

		assertThat(proxy.toString()).isNotBlank();
		assertThat(proxy).hasSameHashCodeAs(proxy);
	}

	@Test
	@DisplayName("default method in a non-public interface")
	void default_in_non_public_interface() {
		NotPublic proxy = pkg(NotPublic.class);

		assertThat(proxy.constantPlusOne()).isEqualTo(43);
		assertThat(proxy.constantPlus(8)).isEqualTo(50);
	}

	@Test
	@DisplayName("an exception thrown by a default method comes out as it is, without a wrapper")
	void exception_from_default_method() {
		assertThatThrownBy(() -> pkg(NotPublic.class).failing())
				.isExactlyInstanceOf(IllegalStateException.class)
				.hasMessage("thrown by the default method");
	}

	@Test
	@DisplayName("equals compares the proxy's identity")
	void equals_with_itself() {

		Public proxy = pkg(Public.class);

		assertThat(proxy.equals(proxy)).isTrue();
		assertThat(proxy.equals(pkg(Public.class))).isFalse();
		assertThat(proxy.equals(null)).isFalse();
	}
}
