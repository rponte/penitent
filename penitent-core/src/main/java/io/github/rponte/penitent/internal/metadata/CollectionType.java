package io.github.rponte.penitent.internal.metadata;

/**
 * An Oracle collection type, with the name JDBC addresses it by and what it holds: scalars (both
 * elements null), a composite type, or another collection.
 *
 * <p>The last one is what makes a collection of collections work: {@code MATRIX_T AS TABLE OF
 * NAME_LIST_T} is a {@code List<List<String>>}, and each inner level is an {@code Array} of its own,
 * with its own name. The name of the element is in the dictionary, as for a composite element.
 *
 * <p>{@code scalarElement} is the Oracle type of a scalar element, as the dictionary spells it, such as
 * {@code TIMESTAMP(6) WITH LOCAL TIME ZONE}, and null otherwise. Most scalars cross without it, but a
 * {@code TIMESTAMP WITH LOCAL TIME ZONE} element is built by the library, because driver 19 builds it without
 * the value's offset: see {@code OracleTimeZoneCodec}.
 */
public record CollectionType(String bindName, CompositeType compositeElement, CollectionType collectionElement,
		String scalarElement) {

	boolean holdsScalars() {
		return compositeElement == null && collectionElement == null;
	}

	@Override
	public String toString() {
		return bindName;
	}
}
