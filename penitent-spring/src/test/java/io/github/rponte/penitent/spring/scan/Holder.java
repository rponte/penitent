package io.github.rponte.penitent.spring.scan;

import io.github.rponte.penitent.PlsqlPackage;

/** Holds a nested interface, as a test class holds its fixtures. */
public final class Holder {

	private Holder() {
	}

	/** Skipped unless considerNested is on. */
	@PlsqlPackage("FIXTURE_SPRING")
	public interface NestedItems {

		int countItems();
	}
}
