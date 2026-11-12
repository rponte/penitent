package io.github.rponte.penitent.mock.docs;

import java.math.BigDecimal;

/** The code under test of the Testing page's examples: it calls a routine, as an application's services do. */
// snippet: testing/service
public class CheckoutService {

    private final Freights freights;

    public CheckoutService(Freights freights) {
        this.freights = freights;
    }

    public BigDecimal total(BigDecimal subtotal, String origin, String destination) {
        return subtotal.add(freights.calculate(origin, destination));
    }
}
// end-snippet
