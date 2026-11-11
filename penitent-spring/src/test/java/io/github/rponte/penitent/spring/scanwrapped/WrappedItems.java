package io.github.rponte.penitent.spring.scanwrapped;

import jakarta.validation.constraints.Positive;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;

/** An interface Spring wraps in its own proxy, for its transaction and its validation. */
@PlsqlPackage("FIXTURE_SPRING")
@Validated
public interface WrappedItems {

	@Transactional
	@PlsqlRoutine("INSERT_ITEM")
	void insert(@Positive int id);

	@PlsqlRoutine("INSERT_ITEM")
	void insertWithoutTransactional(@Positive int id);

	int sessionId();
}
