package io.github.rponte.penitent.mock.internal;

/** A stubbed call, and what it does: its PL/SQL, which ends the routine, by returning or by raising. */
public record Stub(Invocation invocation, Action action) {

	/** What a stub does. */
	public sealed interface Action {
	}

	/** Returns {@code value}, after setting the OUT values; for a procedure, only sets them. */
	public record Return(Object value, boolean function) implements Action {
	}

	/** {@code RAISE_APPLICATION_ERROR(code, message)}. */
	public record ApplicationError(int code, String message) implements Action {
	}

	/** {@code RAISE} one of Oracle's predefined exceptions. */
	public record Predefined(String name) implements Action {
	}
}
