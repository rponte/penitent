package io.github.rponte.penitent.internal.proxy;

import java.sql.Connection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.internal.Caches;
import io.github.rponte.penitent.internal.binding.CursorBinding;
import io.github.rponte.penitent.internal.binding.TypeBinding;
import io.github.rponte.penitent.internal.metadata.BlockMetadata;
import io.github.rponte.penitent.internal.metadata.CollectionType;
import io.github.rponte.penitent.internal.metadata.CompositeType;
import io.github.rponte.penitent.internal.metadata.Dictionary;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/**
 * What a proxy has learned about the types it carries: composites, collections, the types a block
 * declares and the cursors it reads, each looked up once and then reused.
 *
 * <p>Per instance, not static: a static cache is per-classloader state, the same trap that already
 * forced {@code forkCount=1} in the PL/SQL coverage setup.
 */
final class TypeCache {

	private final PlsqlConverters converters;

	private final ConcurrentMap<String, TypeBinding> bindings = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, CollectionType> collectionTypes = new ConcurrentHashMap<>();
	private final ConcurrentMap<String, BlockMetadata.DeclaredType> declaredTypes = new ConcurrentHashMap<>();
	private final ConcurrentMap<Class<?>, CursorBinding> cursors = new ConcurrentHashMap<>();

	TypeCache(PlsqlConverters converters) {
		this.converters = converters;
	}

	/** The converters of the whole interface. */
	PlsqlConverters converters() {
		return converters;
	}

	TypeBinding binding(Class<?> javaType, CompositeType type, Connection connection) {
		String key = javaType.getName() + "@" + type.qualifiedBindName();
		return Caches.get(bindings, key, k -> TypeBinding.of(new Dictionary(connection), javaType, type, converters));
	}

	/**
	 * The collection type, with what it holds, from the dictionary and cached.
	 *
	 * <p>A {@code @PlsqlType} on the element class is no longer needed here: the dictionary
	 * answers for schema-level and package-level collections, with an OBJECT, RECORD or
	 * {@code %ROWTYPE} element. If the annotation is present and disagrees, it fails: that
	 * signals the wrong class for the collection, not a name to be honored.
	 */
	CollectionType collectionType(Argument collection, String schema, Connection connection) {
		String key = collection.ownerOr(schema) + "." + collection.typeName() + "." + collection.typeSubname();
		return Caches.get(collectionTypes, key,
				k -> RoutineMetadata.collectionTypeOf(new Dictionary(connection), collection, schema));
	}

	/** A type a block declares, resolved in {@code schema}. */
	BlockMetadata.DeclaredType declaredType(String schema, String declared, Connection connection) {
		return Caches.get(declaredTypes, schema + "|" + declared,
				k -> BlockMetadata.declaredType(new Dictionary(connection), schema, declared));
	}

	/** How to read a cursor whose rows become {@code elementType}. */
	CursorBinding cursor(Class<?> elementType) {
		return cursors.computeIfAbsent(elementType, t -> CursorBinding.of(t, converters, this::binding));
	}
}
