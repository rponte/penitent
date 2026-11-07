package io.github.rponte.penitent.boot.app;

import io.github.rponte.penitent.PlsqlPackage;

@PlsqlPackage("ORDERS")
public interface Orders {

	int countOrders();

	/** Nested: skipped by the scan, as with {@code @EnablePlsqlPackages} by default. */
	@PlsqlPackage("ORDERS")
	interface Nested {
		int countOrders();
	}
}
