package io.github.rponte.penitent.spring.scanbroken;

import io.github.rponte.penitent.PlsqlPackage;

/** Kept out of the {@code scan} package: an empty name is a mapping error, which stops the context. */
@PlsqlPackage("")
public interface WithoutContainer {

	void anything();
}
