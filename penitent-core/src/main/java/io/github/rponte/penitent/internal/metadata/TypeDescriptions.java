package io.github.rponte.penitent.internal.metadata;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.rponte.penitent.internal.Caches;
import io.github.rponte.penitent.internal.metadata.TypeDescription.TypeName;

/**
 * The types a proxy has described (#169), each read once, when it is first asked for, whole, and kept while the
 * proxy lives, beside the bindings its {@code TypeCache} keeps. The validation of a method, the collections of its
 * parameters and the bindings of their values ask for the same types: they now read them here, instead of each asking
 * the dictionary again. Cleared with the rest of the proxy's types, when one changed in the database.
 *
 * <p>Read on demand, one type at a time, as the types were before: a problem deep in a type, such as one this user
 * cannot see, shows when that type is asked for, not before.
 */
public final class TypeDescriptions {

	private final ConcurrentMap<CompositeType, TypeDescription.Composite> composites = new ConcurrentHashMap<>();
	private final ConcurrentMap<TypeName, TypeDescription.Collection> collections = new ConcurrentHashMap<>();
	private final ConcurrentMap<TypeName, Optional<String>> typecodes = new ConcurrentHashMap<>();

	/** {@code type}'s attributes, read from {@code dictionary} if it was not described yet. */
	public TypeDescription.Composite composite(CompositeType type, Dictionary dictionary) {
		return Caches.get(composites, type, t -> new TypeDescription.Composite(t,
				dictionary.attributesOf(t.kind(), t.schema(), t.container(), t.simpleName())));
	}

	/** The collection {@code name}, of a package or of the schema, read from {@code dictionary} if it was not yet. */
	public TypeDescription.Collection collection(TypeName name, Dictionary dictionary) {
		return Caches.get(collections, name, n -> new TypeDescription.Collection(n,
				dictionary.elementOf(n.owner(), n.packageName(), n.name()).orElse(null),
				dictionary.elementTypeNameOf(n.owner(), n.packageName(), n.name()).orElse(""),
				dictionary.indexByOf(n.owner(), n.packageName(), n.name()).orElse(null)));
	}

	/** The typecode of {@code name}, as {@code OBJECT} or {@code COLLECTION}, or empty if it is not there. */
	public Optional<String> typecode(TypeName name, Dictionary dictionary) {
		return Caches.get(typecodes, name,
				n -> dictionary.typecodeOf(n.owner(), n.packageName(), n.name()));
	}

	/** Forgets every type it described: the next request reads them again. */
	public void clear() {
		composites.clear();
		collections.clear();
		typecodes.clear();
	}
}
