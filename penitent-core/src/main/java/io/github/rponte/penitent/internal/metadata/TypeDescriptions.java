package io.github.rponte.penitent.internal.metadata;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.rponte.penitent.internal.metadata.TypeDescription.TypeName;

/**
 * The types a proxy has described (#169), each read once, whole, and kept while the proxy lives, beside the bindings
 * its {@code TypeCache} keeps. The validation of a method, the collections of its parameters and the bindings of their
 * values ask for the same types: they read them here, instead of each asking the dictionary again. Cleared with the
 * rest of the proxy's types, when one changed in the database.
 *
 * <p><b>Read by level.</b> A type asked for is read, and then the types it refers to are read ahead, a level at a time:
 * the typecodes of the types its attributes and its element name, in a query per owner and package, then those
 * collections in a query, then those composites' attributes in a query, and so on. So a type nested several levels
 * deep costs a query per view and level, not per type. Never a recursive query over the views, which #173 measured
 * far slower.
 *
 * <p><b>Reading ahead raises nothing.</b> A type read ahead that is not there is kept as not there, as reading it on
 * demand would; and a failure reading ahead stops it, quietly, leaving the rest to be read on demand. Only what is
 * asked for raises, when it is asked for: a problem deep in a type shows when it did before (#178).
 */
public final class TypeDescriptions {

	/** How many levels are read ahead: well beyond any type, and a bound if a type ever referred to itself. */
	private static final int MAX_LEVELS = 32;

	private final ConcurrentMap<CompositeType, TypeDescription.Composite> composites = new ConcurrentHashMap<>();
	private final ConcurrentMap<TypeName, TypeDescription.Collection> collections = new ConcurrentHashMap<>();
	private final ConcurrentMap<TypeName, Optional<String>> typecodes = new ConcurrentHashMap<>();

	/** {@code type}'s attributes, none if it is not there; read, with what it refers to, if it was not described yet. */
	public TypeDescription.Composite composite(CompositeType type, Dictionary dictionary) {
		TypeDescription.Composite known = composites.get(type);
		if (known != null) {
			return known;
		}
		read(dictionary, Set.of(type), Set.of());
		return composites.get(type);
	}

	/** The collection {@code name}, of a package or of the schema; read, with what it holds, if it was not yet. */
	public TypeDescription.Collection collection(TypeName name, Dictionary dictionary) {
		TypeDescription.Collection known = collections.get(name);
		if (known != null) {
			return known;
		}
		read(dictionary, Set.of(), Set.of(name));
		return collections.get(name);
	}

	/** The typecode of {@code name}, as {@code OBJECT} or {@code COLLECTION}, or empty if it is not there. */
	public Optional<String> typecode(TypeName name, Dictionary dictionary) {
		Optional<String> known = typecodes.get(name);
		if (known != null) {
			return known;
		}
		readTypecodes(dictionary, Set.of(name));
		return typecodes.get(name);
	}

	/** Forgets every type it described: the next request reads them again. */
	public void clear() {
		composites.clear();
		collections.clear();
		typecodes.clear();
	}

	/** The types asked for, as on demand, then what they refer to, ahead. */
	private void read(Dictionary dictionary, Set<CompositeType> askedComposites, Set<TypeName> askedCollections) {
		Level level = readLevel(dictionary, askedComposites, askedCollections);
		try {
			for (int i = 0; i < MAX_LEVELS && !level.isEmpty(); i++) {
				Wanted next = referredBy(dictionary, level);
				level = readLevel(dictionary, next.composites(), next.collections());
			}
		} catch (RuntimeException e) {
			// reading ahead only: what was not read is read on demand, and raises then, as it did
		}
	}

	/** What one level read: the composites and collections it described. */
	private record Level(Set<TypeDescription.Composite> composites, Set<TypeDescription.Collection> collections) {

		boolean isEmpty() {
			return composites.isEmpty() && collections.isEmpty();
		}
	}

	/** What the next level reads: the composites and collections a level refers to. */
	private record Wanted(Set<CompositeType> composites, Set<TypeName> collections) {
	}

	/** The composites and collections not described yet, each kind in a query per owner and package. */
	private Level readLevel(Dictionary dictionary, Set<CompositeType> wantedComposites, Set<TypeName> wantedCollections) {
		Set<TypeDescription.Composite> readComposites = new HashSet<>();
		Map<List<Object>, List<CompositeType>> compositeGroups = new LinkedHashMap<>();
		for (CompositeType type : wantedComposites) {
			if (!composites.containsKey(type)) {
				compositeGroups.computeIfAbsent(List.of(type.kind(), type.schema(), String.valueOf(type.container())),
						k -> new java.util.ArrayList<>()).add(type);
			}
		}
		for (List<CompositeType> group : compositeGroups.values()) {
			CompositeType first = group.get(0);
			Map<String, List<Dictionary.Attribute>> attributes = dictionary.attributesOf(first.kind(), first.schema(),
					first.container(), group.stream().map(CompositeType::simpleName).toList());
			for (CompositeType type : group) {
				TypeDescription.Composite described = new TypeDescription.Composite(type,
						List.copyOf(attributes.getOrDefault(type.simpleName(), List.of())));
				if (composites.putIfAbsent(type, described) == null) {
					readComposites.add(described);
				}
			}
		}

		Set<TypeDescription.Collection> readCollections = new HashSet<>();
		Map<List<String>, List<TypeName>> collectionGroups = new LinkedHashMap<>();
		Set<TypeName> pendingCollections = new HashSet<>(wantedCollections);
		for (TypeName name : pendingCollections) {
			if (!collections.containsKey(name)) {
				collectionGroups.computeIfAbsent(java.util.Arrays.asList(name.owner(), name.packageName()),
						k -> new java.util.ArrayList<>()).add(name);
			}
		}
		for (List<TypeName> group : collectionGroups.values()) {
			TypeName first = group.get(0);
			Map<String, Dictionary.CollectionOf> found = dictionary.collectionsOf(first.owner(), first.packageName(),
					group.stream().map(TypeName::name).toList());
			for (TypeName name : group) {
				Dictionary.CollectionOf collection = found.get(name.name());
				Dictionary.Element element = collection == null ? null : collection.element();
				TypeDescription.Collection described = new TypeDescription.Collection(name, element,
						element == null ? "" : element.name(), collection == null ? null : collection.indexBy());
				if (collections.putIfAbsent(name, described) == null) {
					readCollections.add(described);
				}
			}
		}
		return new Level(readComposites, readCollections);
	}

	/**
	 * The composites and collections the types of {@code level} refer to: the typecodes of the types named are read
	 * first, all of them at once, and decide which each is. The rules are those of reading on demand: an attribute's
	 * type, in {@code TypeBinding.shapeOf}; a collection's element, in {@code RoutineMetadata.collectionTypeOf}. A type
	 * whose typecode is not there is not read ahead: asked for, it raises what it raises.
	 */
	private Wanted referredBy(Dictionary dictionary, Level level) {
		Map<TypeName, String> attributeTypes = new LinkedHashMap<>();
		for (TypeDescription.Composite composite : level.composites()) {
			for (Dictionary.Attribute attribute : composite.attributes()) {
				if (attribute.isRef() || attribute.typeOwner() == null && attribute.typePackage() == null) {
					continue;
				}
				String owner = attribute.typeOwner() != null ? attribute.typeOwner() : composite.type().schema();
				attributeTypes.put(new TypeName(owner, attribute.typePackage(), attribute.typeName()), null);
			}
		}
		Map<TypeName, CompositeType> elementComposites = new LinkedHashMap<>();
		Set<TypeName> elementTypes = new HashSet<>();
		for (TypeDescription.Collection collection : level.collections()) {
			Dictionary.Element element = collection.element();
			if (element == null || element.isRef()) {
				continue;
			}
			String schema = collection.name().owner();
			String owner = element.owner() != null ? element.owner() : schema;
			boolean mayBeCollection = element.packageName() != null || element.owner() != null && element.name() != null
					&& !element.name().endsWith("%ROWTYPE");
			TypeName named = new TypeName(owner, element.packageName(), element.name());
			if (mayBeCollection) {
				elementTypes.add(named);
			}
			CompositeType composite = RoutineMetadata.elementOf(schema, element.owner(), element.packageName(),
					element.name());
			if (composite != null) {
				elementComposites.put(named, composite);
			}
		}
		Set<TypeName> named = new HashSet<>(attributeTypes.keySet());
		named.addAll(elementTypes);
		readTypecodes(dictionary, named);

		Set<CompositeType> next = new HashSet<>();
		Set<TypeName> nextCollections = new HashSet<>();
		for (TypeName type : attributeTypes.keySet()) {
			Optional<String> typecode = typecodes.getOrDefault(type, Optional.empty());
			if (typecode.filter("COLLECTION"::equals).isPresent()) {
				nextCollections.add(type);
			} else if (typecode.isPresent()) {
				next.add(type.packageName() != null
						? new CompositeType(CompositeType.Kind.PLSQL_RECORD, type.owner(), type.packageName(),
								type.name(), type.packageName() + "." + type.name())
						: new CompositeType(CompositeType.Kind.OBJECT, type.owner(), null, type.name(), type.name()));
			}
		}
		for (Map.Entry<TypeName, CompositeType> element : elementComposites.entrySet()) {
			boolean collection = elementTypes.contains(element.getKey()) && typecodes
					.getOrDefault(element.getKey(), Optional.empty()).filter("COLLECTION"::equals).isPresent();
			if (collection) {
				nextCollections.add(element.getKey());
			} else {
				next.add(element.getValue());
			}
		}
		return new Wanted(next, nextCollections);
	}

	/** The typecodes of {@code names} not known yet, in a query per owner and package; empty for one not there. */
	private void readTypecodes(Dictionary dictionary, Set<TypeName> names) {
		Map<List<String>, List<TypeName>> groups = new LinkedHashMap<>();
		for (TypeName name : names) {
			if (!typecodes.containsKey(name)) {
				groups.computeIfAbsent(java.util.Arrays.asList(name.owner(), name.packageName()),
						k -> new java.util.ArrayList<>()).add(name);
			}
		}
		for (List<TypeName> group : groups.values()) {
			TypeName first = group.get(0);
			Map<String, String> found = dictionary.typecodesOf(first.owner(), first.packageName(),
					group.stream().map(TypeName::name).toList());
			for (TypeName name : group) {
				typecodes.putIfAbsent(name, Optional.ofNullable(found.get(name.name())));
			}
		}
	}
}
