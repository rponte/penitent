package io.github.rponte.penitent;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Objects;

import javax.sql.DataSource;

import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.DefaultPlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlExecutionException;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.exception.PlsqlRoutineRaisedException;
import io.github.rponte.penitent.internal.proxy.RoutineInvocationHandler;

/**
 * Creates the implementation of an interface that maps PL/SQL routines, and checks it against the
 * database.
 *
 * <pre>{@code
 * @PlsqlPackage("FREIGHT_CALCULATOR")
 * public interface FreightCalculator {
 *     // FUNCTION calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER
 *     BigDecimal calculateFreight(String originState, String destinationState);
 * }
 *
 * FreightCalculator freight = PlsqlPackages.create(FreightCalculator.class, dataSource);
 * BigDecimal value = freight.calculateFreight("SP", "CE");
 * }</pre>
 *
 * <p>The interface declares its container with {@link PlsqlPackage} or {@link PlsqlSchema}; one made
 * only of {@link PlsqlBlock} methods needs neither. Each method is checked against the dictionary on
 * its first call, and what it read is kept for the life of the proxy; {@link #validate} checks every
 * method at once.
 *
 * <p>The implementation is a JDK {@link Proxy}, safe to share between threads. Each call obtains a
 * connection from the {@code DataSource}, uses only that one and closes it; the library never
 * commits, rolls back or changes auto-commit. For calls to take part in a transaction, either pass a
 * {@code DataSource} that returns the transaction's connection, as {@code SpringPlsqlPackages} in
 * {@code penitent-spring} does, or bind a view of the proxy to the connection with {@link #using}.
 *
 * <p>Parameters bind by <b>position</b>, unless the interface or the settings say
 * {@link PlsqlBind#BY_NAME}; the attributes of a composite type always bind by <b>name</b>, because
 * the order of the Java fields does not follow the order of the type's attributes.
 */
public final class PlsqlPackages {

	private PlsqlPackages() {
	}

	/**
	 * A routine error arrives as {@link PlsqlRoutineRaisedException}, and every other database
	 * failure as {@link PlsqlExecutionException}.
	 */
	public static <T> T create(Class<T> type, DataSource dataSource) {
		return create(type, dataSource, PlsqlSettings.defaults());
	}

	/** With a translator that decides which exception the caller receives when the database fails. */
	public static <T> T create(Class<T> type, DataSource dataSource, PlsqlExceptionTranslator translator) {
		return create(type, dataSource, PlsqlSettings.defaults().withExceptionTranslator(translator));
	}

	/** With converters that apply across the whole interface. See {@link PlsqlConverters}. */
	public static <T> T create(Class<T> type, DataSource dataSource, PlsqlConverters converters) {
		return create(type, dataSource, PlsqlSettings.defaults().withConverters(converters));
	}

	/** With a translator and converters; a shortcut for the same {@link PlsqlSettings}. */
	public static <T> T create(Class<T> type, DataSource dataSource, PlsqlExceptionTranslator translator,
			PlsqlConverters converters) {
		return create(type, dataSource,
				PlsqlSettings.defaults().withExceptionTranslator(translator).withConverters(converters));
	}

	/**
	 * With {@link PlsqlSettings}, usually the same object for every interface of an application. Without
	 * an exception translator in the settings, it is {@link DefaultPlsqlExceptionTranslator}.
	 */
	public static <T> T create(Class<T> type, DataSource dataSource, PlsqlSettings settings) {
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(dataSource, "dataSource");
		Objects.requireNonNull(settings, "settings");

		if (!type.isInterface()) {
			throw new PlsqlMappingException(type.getName() + " must be an interface");
		}
		requireOneContainer(type);

		PlsqlExceptionTranslator translator = settings.exceptionTranslator()
				.orElseGet(DefaultPlsqlExceptionTranslator::new);
		Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type },
				new RoutineInvocationHandler(type, dataSource, translator, settings.converters(), settings.bind()));
		return type.cast(proxy);
	}

	/**
	 * A view of {@code proxy} bound to {@code connection}, so that its calls take part in a transaction
	 * the application, or its framework, controls:
	 *
	 * <pre>{@code
	 * Orders orders = PlsqlPackages.create(Orders.class, dataSource);   // once, as usual
	 *
	 * try (Connection connection = dataSource.getConnection()) {
	 *     connection.setAutoCommit(false);
	 *     Orders inTransaction = PlsqlPackages.using(orders, connection);
	 *     inTransaction.placeOrder(42, total, orderId);
	 *     // ... plain JDBC on the same connection ...
	 *     connection.commit();
	 * }
	 * }</pre>
	 *
	 * <p>The view implements the same interface, and shares the proxy's dictionary cache, settings and
	 * exception translator: creating it reads nothing, and a method the proxy already called reads
	 * nothing either. Every call of the view, and its {@link #validate}, runs on {@code connection},
	 * which the view never closes, commits or rolls back, nor changes its auto-commit: the connection,
	 * and its transaction, stay the application's.
	 *
	 * <p>A view is cheap, and meant to live as long as the transaction: one per transaction is the
	 * intended use, and an unreferenced view is collected while the proxy lives on. Like the
	 * connection, a view should not be used by two threads at once; the proxy it came from still can.
	 *
	 * @throws IllegalArgumentException if {@code proxy} was not created by this library
	 */
	public static <T> T using(T proxy, Connection connection) {
		Objects.requireNonNull(proxy, "proxy");
		Objects.requireNonNull(connection, "connection");
		RoutineInvocationHandler handler = handlerOf(proxy);
		Class<?> type = handler.type();
		@SuppressWarnings("unchecked")
		T view = (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler.boundTo(connection));
		return view;
	}

	/**
	 * Checks every method of the proxy's interface against the database, without calling any routine,
	 * so that a mapping error shows up now rather than on the first call. It uses one connection, and
	 * leaves what it read cached: after it, no call reads the dictionary.
	 *
	 * <p>It checks what a call would check before running: that the routine exists, the parameters,
	 * procedure or function, and the composite types the method declares, all the way down. A cursor's
	 * columns and a composite sent as a subclass of the declared type are still checked on the call,
	 * since they depend on the value. An anonymous block has no signature in the dictionary, so only
	 * its placeholders are checked against the method.
	 *
	 * @throws PlsqlMappingException with every method that does not match, one per line
	 * @throws IllegalArgumentException if {@code proxy} was not created by this library
	 */
	public static void validate(Object proxy) {
		Objects.requireNonNull(proxy, "proxy");
		handlerOf(proxy).validate();
	}

	private static RoutineInvocationHandler handlerOf(Object proxy) {
		if (!Proxy.isProxyClass(proxy.getClass())
				|| !(Proxy.getInvocationHandler(proxy) instanceof RoutineInvocationHandler handler)) {
			throw new IllegalArgumentException(proxy.getClass().getName() + " is not a proxy created by PlsqlPackages");
		}
		return handler;
	}

	/**
	 * The interface is the container of its routines, as the package or the schema is in Oracle: it
	 * declares exactly one. An empty package name used to mean the schema, and is now an error, so that
	 * a name erased by mistake does not silently switch containers.
	 */
	private static void requireOneContainer(Class<?> type) {
		PlsqlPackage pkg = type.getAnnotation(PlsqlPackage.class);
		PlsqlSchema schema = type.getAnnotation(PlsqlSchema.class);
		if (pkg != null && schema != null) {
			throw new PlsqlMappingException(type.getName() + " declares both @PlsqlPackage and @PlsqlSchema;"
					+ " an interface maps one container: a package, or the routines outside a package");
		}
		if (pkg != null && pkg.value().isBlank()) {
			throw new PlsqlMappingException(type.getName() + " declares @PlsqlPackage without a name; for the"
					+ " routines outside a package, use @PlsqlSchema");
		}
		if (pkg == null && schema == null && !onlyBlocks(type)) {
			throw new PlsqlMappingException(type.getName()
					+ " needs @PlsqlPackage or @PlsqlSchema, or all of its methods must be @PlsqlBlock"
					+ " (an anonymous block belongs to no container)");
		}
	}

	/** An anonymous block does not live in a package, so an interface made only of blocks does not need the annotation. */
	private static boolean onlyBlocks(Class<?> type) {
		Method[] methods = java.util.Arrays.stream(type.getMethods())
				.filter(m -> !m.isDefault() && m.getDeclaringClass() != Object.class)
				.toArray(Method[]::new);
		return methods.length > 0
				&& java.util.Arrays.stream(methods).allMatch(m -> m.getAnnotation(PlsqlBlock.class) != null);
	}
}
