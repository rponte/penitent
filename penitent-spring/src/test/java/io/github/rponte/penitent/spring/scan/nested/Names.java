package io.github.rponte.penitent.spring.scan.nested;

import java.util.List;

import io.github.rponte.penitent.PlsqlPackage;

/** In a subpackage, to show that subpackages are scanned too. */
@PlsqlPackage("FIXTURE_SPRING")
public interface Names {

	List<String> echoNames(List<String> value);
}
