package io.github.rponte.penitent.spring.scan;

import io.github.rponte.penitent.PlsqlPackage;

@PlsqlPackage("FIXTURE_SPRING")
public interface Items {

	int countItems();

	void insertItem(int id);
}
