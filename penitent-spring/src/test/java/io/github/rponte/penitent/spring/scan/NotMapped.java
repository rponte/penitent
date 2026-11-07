package io.github.rponte.penitent.spring.scan;

/** An ordinary interface in a scanned package: it must not become a bean. */
public interface NotMapped {

	int anything();
}
