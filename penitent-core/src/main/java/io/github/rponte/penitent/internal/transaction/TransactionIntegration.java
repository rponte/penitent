package io.github.rponte.penitent.internal.transaction;

import java.util.List;
import java.util.ServiceLoader;

import javax.sql.DataSource;

/**
 * What another transaction model, such as Spring's, tells the core about itself, so that a
 * {@code PlsqlTransaction} never overlaps it without a word. The core does not know that model: the
 * module that integrates it, such as {@code penitent-spring}, registers an implementation in
 * {@code META-INF/services}, and {@link ServiceLoader} finds it on the classpath.
 *
 * <p>Not part of the API: it changes with the modules that implement it, which are released together
 * with the core.
 */
public interface TransactionIntegration {

	/** Throws {@link IllegalStateException} when a block must not open on the current thread. */
	void refuseBlock();

	/**
	 * The {@code DataSource} whose connections {@code dataSource} hands out, when it is a wrapper of this
	 * model that only makes them take part in its transactions; otherwise {@code dataSource} itself.
	 */
	DataSource target(DataSource dataSource);

	/** The implementations on the classpath, found once. */
	static List<TransactionIntegration> all() {
		return Loaded.ALL;
	}

	/** {@code dataSource} as every implementation sees it: see {@link #target(DataSource)}. */
	static DataSource targetOf(DataSource dataSource) {
		DataSource target = dataSource;
		for (TransactionIntegration integration : all()) {
			target = integration.target(target);
		}
		return target;
	}

	/** Holds the implementations, loaded on first use. */
	final class Loaded {

		private static final List<TransactionIntegration> ALL = ServiceLoader
				.load(TransactionIntegration.class, TransactionIntegration.class.getClassLoader())
				.stream()
				.map(ServiceLoader.Provider::get)
				.toList();

		private Loaded() {
		}
	}
}
