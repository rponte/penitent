package io.github.rponte.penitent.spring.scansettings;

import java.util.List;

import io.github.rponte.penitent.PlsqlPackage;
import io.github.rponte.penitent.PlsqlRoutine;

/** Needs the converter of the PlsqlSettings bean: without it, Tag is not a type the library knows. */
@PlsqlPackage("FIXTURE_SPRING")
public interface Tags {

	record Tag(String value) {
	}

	@PlsqlRoutine("ECHO_NAMES")
	List<Tag> echo(List<Tag> value);

	void raiseError();
}
