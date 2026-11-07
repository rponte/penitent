package io.github.rponte.penitent.boot.app;

import io.github.rponte.penitent.PlsqlBlock;

/** No annotation on the type: found by its {@code @PlsqlBlock} method. */
public interface Arithmetic {

	@PlsqlBlock("begin :result := :a + :b; end;")
	int sum(int a, int b);
}
