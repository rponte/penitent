package io.github.rponte.penitent.spring.scan;

import io.github.rponte.penitent.PlsqlBlock;

/** Only anonymous blocks: no annotation on the type, and the scan still finds it. */
public interface Arithmetic {

	@PlsqlBlock("BEGIN :result := :a + :b; END;")
	int sum(int a, int b);
}
