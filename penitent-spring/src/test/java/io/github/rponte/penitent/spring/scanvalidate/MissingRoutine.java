package io.github.rponte.penitent.spring.scanvalidate;

import io.github.rponte.penitent.PlsqlPackage;

/** Creates fine, and fails only against the database: what validateOnStartup is meant to catch. */
@PlsqlPackage("FIXTURE_SPRING")
public interface MissingRoutine {

	int countItems();

	void routineThatDoesNotExist();
}
