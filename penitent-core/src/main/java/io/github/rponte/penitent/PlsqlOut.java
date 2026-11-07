package io.github.rponte.penitent;

import io.github.rponte.penitent.internal.proxy.PlsqlOutAccess;

/**
 * An {@code OUT} or {@code IN OUT} parameter. It sits at the same position the parameter has in the
 * routine, and the value returned by the database is read with {@link #get()} after the call:
 *
 * <pre>{@code
 * // PROCEDURE add_numbers(p_a NUMBER, p_b NUMBER, p_total OUT NUMBER)
 * void addNumbers(int a, int b, PlsqlOut<Integer> total);
 *
 * PlsqlOut<Integer> total = new PlsqlOut<>();
 * calculator.addNumbers(1, 2, total);
 * total.get(); // 3
 * }</pre>
 *
 * <p>For an {@code IN OUT}, the input value comes from {@link #of(Object)}. {@code T} is the Java
 * type, with the same rules as a return value: a scalar, a composite type, or {@code List<E>} for a
 * collection or cursor. If the call fails, the value stays what it was.
 */
public final class PlsqlOut<T> {

	static {
		PlsqlOutAccess.install((out, value) -> out.set(value));
	}

	private T value;

	public PlsqlOut() {
	}

	/** An {@code IN OUT}: the database receives {@code value} and replaces it with whatever it returns. */
	public static <T> PlsqlOut<T> of(T value) {
		PlsqlOut<T> out = new PlsqlOut<>();
		out.value = value;
		return out;
	}

	public T get() {
		return value;
	}

	@SuppressWarnings("unchecked")
	void set(Object value) {
		this.value = (T) value;
	}

	@Override
	public String toString() {
		return "PlsqlOut[" + value + "]";
	}
}
