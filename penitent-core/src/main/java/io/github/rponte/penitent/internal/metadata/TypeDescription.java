package io.github.rponte.penitent.internal.metadata;

import java.util.List;

/**
 * An Oracle type as the dictionary describes it, whole (#169): a composite and its attributes, or a collection and
 * what it holds. Oracle's only, nothing of Java, which {@code TypeBinding} joins to it. A type it refers to, an
 * attribute's or an element's, is named, not embedded: whoever needs it asks {@link TypeDescriptions} for it, when it
 * needs it, so that a problem deep in a type shows when it does today, not before (#178).
 */
public sealed interface TypeDescription {

	/** Where a type is: its owner, its package or {@code null}, its name, as the dictionary refers to it. */
	record TypeName(String owner, String packageName, String name) {
	}

	/** An OBJECT, a package RECORD or a {@code %ROWTYPE}, and its attributes, in order: none if it is not there. */
	record Composite(CompositeType type, List<Dictionary.Attribute> attributes) implements TypeDescription {

		/** The names of its JSON attributes, or columns behind a {@code %ROWTYPE}: an OBJECT cannot have any. */
		public List<String> jsonAttributes() {
			return type.kind() == CompositeType.Kind.OBJECT ? List.of()
					: attributes.stream().filter(a -> "JSON".equals(a.typeName())).map(Dictionary.Attribute::name)
							.toList();
		}
	}

	/**
	 * A collection: what it holds, as the dictionary lists it, or {@code null} if it is not there; its element's type
	 * name as the dictionary spells it, even a scalar's, as {@code TIMESTAMP(6) WITH TIME ZONE}, or empty; and its
	 * {@code INDEX BY}, as {@code PLS_INTEGER}, or {@code null}.
	 */
	record Collection(TypeName name, Dictionary.Element element, String elementTypeName, String indexBy)
			implements TypeDescription {
	}
}
