package io.github.rponte.penitent.internal.binding;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.ServiceLoader;

import io.github.rponte.penitent.internal.log.CallLog;
import io.github.rponte.penitent.internal.metadata.CompositeType;

/**
 * Where another module, such as {@code penitent-test}, gives the driver another name for a type on a connection. Every
 * name of a composite or a collection that the core hands the driver, to build a value or to read one back, goes
 * through here. The core does not know why a name changes: the module registers an implementation in
 * {@code META-INF/services}, and {@link ServiceLoader} finds it on the classpath. With none, which is the case in
 * production, every name stays as it is.
 *
 * <p>The contract: the name given back is {@code name} itself, or that of a type of the same shape, the same
 * attributes in the same order, that the driver can resolve on {@code connection}. More than one implementation
 * applies in sequence, each one taking the name the previous one gave.
 *
 * <p>Not part of the API: it changes with the modules that implement it, which are released together with the core.
 */
public interface TypeNameIntegration {

	/** The name to give the driver for {@code type} on {@code connection}, which is {@code name} so far. */
	String compositeName(Connection connection, CompositeType type, String name) throws SQLException;

	/**
	 * The same, for a collection. It takes the name alone: where a collection is bound with a null or registered as
	 * an {@code OUT}, the name from the dictionary is all there is.
	 */
	String collectionName(Connection connection, String name) throws SQLException;

	/** The implementations on the classpath, found once. */
	static List<TypeNameIntegration> all() {
		return Loaded.ALL;
	}

	/** The name of {@code type} on {@code connection}, as every implementation sees it. */
	static String compositeNameOf(Connection connection, CompositeType type) throws SQLException {
		String original = type.qualifiedBindName();
		String name = original;
		for (TypeNameIntegration integration : all()) {
			name = integration.compositeName(connection, type, name);
		}
		return renamed(original, name);
	}

	/** The name of the collection {@code name} on {@code connection}, as every implementation sees it. */
	static String collectionNameOf(Connection connection, String name) throws SQLException {
		String renamed = name;
		for (TypeNameIntegration integration : all()) {
			renamed = integration.collectionName(connection, renamed);
		}
		return renamed(name, renamed);
	}

	private static String renamed(String original, String name) {
		if (!name.equals(original)) {
			CallLog.typeRenamed(original, name);
		}
		return name;
	}

	/** Holds the implementations, loaded on first use. */
	final class Loaded {

		private static final List<TypeNameIntegration> ALL = ServiceLoader
				.load(TypeNameIntegration.class, TypeNameIntegration.class.getClassLoader())
				.stream()
				.map(ServiceLoader.Provider::get)
				.toList();

		private Loaded() {
		}
	}
}
