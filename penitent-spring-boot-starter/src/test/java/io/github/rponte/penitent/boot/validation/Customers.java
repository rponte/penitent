package io.github.rponte.penitent.boot.validation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;

import io.github.rponte.penitent.PlsqlPackage;

/** Constraints on the interface itself, as on any Spring bean: checked before the routine is called. */
@PlsqlPackage("CUSTOMERS")
@Validated
public interface Customers {

	record Customer(@NotBlank String name, @Positive int age) {
	}

	void rename(@Positive long id, @NotBlank @Size(max = 60) String name);

	void register(@Valid Customer customer);
}
