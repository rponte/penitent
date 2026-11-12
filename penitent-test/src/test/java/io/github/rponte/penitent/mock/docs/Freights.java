package io.github.rponte.penitent.mock.docs;

import java.math.BigDecimal;

import io.github.rponte.penitent.PlsqlOut;
import io.github.rponte.penitent.PlsqlPackage;

// snippet: testing/interface
@PlsqlPackage("FREIGHTS")
public interface Freights {

    BigDecimal calculate(String pOrigin, String pDestination);

    void quote(String pUf, PlsqlOut<BigDecimal> pValue);

    int countItems(String pUf);
}
// end-snippet
