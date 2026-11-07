package io.github.rponte.penitent.boot.other;

import io.github.rponte.penitent.PlsqlPackage;

/** Outside the application's package. */
@PlsqlPackage("INVOICES")
public interface Invoices {

	int countInvoices();
}
