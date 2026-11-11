package io.github.rponte.penitent.boot.resilience;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.rponte.penitent.PlsqlPackage;

/** Resilience4j's annotations on the interface's methods, as on any Spring bean. */
@PlsqlPackage("PAYMENTS")
public interface Payments {

	@Retry(name = "payments")
	void pay(long id);

	@CircuitBreaker(name = "payments")
	void refund(long id);
}
