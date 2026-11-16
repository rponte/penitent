package io.github.rponte.penitent.mock.internal;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.internal.metadata.CompositeType;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.proxy.Views;

/**
 * One test's mocks: its edition, the stubs of each package, compiled into the edition as they come, the subtypes its
 * {@code %ROWTYPE} records go by there, and the calls recorded, for {@code verify}. Everything that is DDL runs on a
 * connection of its own, outside any transaction of the test, since DDL commits.
 */
public final class MockSession implements RowTypes {

	/** The version of {@code penitent-mock-schema.sql} this library expects. */
	static final int SCHEMA_VERSION = 1;

	private static final String PREFIX = "PENITENT_MOCK_";

	/**
	 * The editions of the mocks that began in this JVM and have not ended. One found in the database and not here was
	 * left by a run that died, and is dropped; one found here belongs to mocks still on, and is left alone.
	 */
	private static final java.util.Set<String> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private final EditionDataSource dataSource;
	private final DataSource base;
	private final Map<Class<?>, Object> describers = new HashMap<>();
	private final Map<String, String> bodies = new HashMap<>();
	private final Map<String, List<Stub>> stubsByPackage = new LinkedHashMap<>();
	private final Map<String, String> rowTypes = new HashMap<>();
	/** The packages a script replaced in this test, which a stub would undo, as {@code SCHEMA.NAME}. */
	private final java.util.Set<String> scriptedPackages = new java.util.HashSet<>();
	private volatile String edition;
	/** The schema of the mocks, whose packages have editions: where the subtypes of {@code %ROWTYPE} records go. */
	private String schema;
	private String defaultEdition;

	public MockSession(EditionDataSource dataSource) {
		this.dataSource = dataSource;
		this.base = dataSource.target();
	}

	// ---------------------------------------------------------------- the edition

	public void begin() {
		if (edition != null) {
			throw new IllegalStateException("the mocks already began, in edition " + edition + ": end them first");
		}
		long start = System.nanoTime();
		try (Connection connection = connection()) {
			Prerequisites.require(connection, SCHEMA_VERSION);
			schema = single(connection, "SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') FROM DUAL");
			defaultEdition = single(connection,
					"SELECT PROPERTY_VALUE FROM DATABASE_PROPERTIES WHERE PROPERTY_NAME = 'DEFAULT_EDITION'");
			dropLeftovers(connection);
			String created = PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
			EditionDataSource.execute(connection, "CREATE EDITION " + created + " AS CHILD OF " + defaultEdition);
			ACTIVE.add(created);
			edition = created;
			MockLog.began(created, start);
		} catch (SQLException e) {
			throw new IllegalStateException("failed to create the test's edition: " + e.getMessage(), e);
		}
		dataSource.enter(edition, defaultEdition, this);
	}

	public void end() {
		if (edition == null) {
			return;
		}
		String ending = edition;
		dataSource.enter(null, defaultEdition, null);
		edition = null;
		stubsByPackage.clear();
		bodies.clear();
		scriptedPackages.clear();
		synchronized (rowTypes) {
			rowTypes.clear();
		}
		// ended either way: an edition that cannot be dropped now is a leftover, for the next begin to drop
		ACTIVE.remove(ending);
		long start = System.nanoTime();
		try (Connection connection = connection()) {
			drop(connection, ending);
			MockLog.ended(ending, start);
		} catch (SQLException e) {
			throw new IllegalStateException("failed to drop the test's edition " + ending + " (a connection taken in the"
					+ " test may still be open, and in it): " + e.getMessage(), e);
		}
	}

	/**
	 * Editions left by a run that died: there can be only one child of the default edition. One of mocks still on in
	 * this JVM is not a leftover: these mocks are refused instead, since only one test mocks a database at a time.
	 */
	private static void dropLeftovers(Connection connection) throws SQLException {
		List<String> found = strings(connection,
				"SELECT EDITION_NAME FROM ALL_EDITIONS WHERE EDITION_NAME LIKE 'PENITENT\\_MOCK\\_%' ESCAPE '\\'");
		for (String edition : found) {
			if (ACTIVE.contains(edition)) {
				throw new IllegalStateException("other mocks began on this database, in edition " + edition + ", and have"
						+ " not ended: tests that mock a database run one at a time, since an edition can have only one"
						+ " child, and two pools of the same database cannot share mocks yet");
			}
		}
		for (String leftover : found) {
			drop(connection, leftover);
			MockLog.leftover(leftover);
		}
	}

	private static void drop(Connection connection, String edition) throws SQLException {
		EditionDataSource.execute(connection, "DROP EDITION " + edition + " CASCADE");
		try (PreparedStatement delete = connection.prepareStatement(
				"DELETE FROM PENITENT_MOCK_CALLS WHERE EDITION_NAME = ?")) {
			delete.setString(1, edition);
			delete.executeUpdate();
		}
		if (!connection.getAutoCommit()) {
			connection.commit();
		}
	}

	// ---------------------------------------------------------------- stubbing

	@SuppressWarnings("unchecked")
	public <T> T mock(Class<T> type) {
		requireBegun();
		describerOf(type);
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, new MockHandler(this, type));
	}

	/**
	 * Adds {@code stub}, or replaces {@code replaced} with it, and compiles its package again. Gives back the stub as it
	 * is kept, with its object types and collections written as PL/SQL: that is the one to replace next time.
	 */
	public Stub stub(Stub stub, Stub replaced) {
		requireBegun();
		long start = System.nanoTime();
		RoutineMetadata routine = routineOf(stub.invocation());
		requireMatchable(routine, byParameter(routine, stub.invocation()));
		requireNotScripted(routine);
		stub = written(stub, routine);
		String pkg = packageOf(routine);
		List<Stub> stubs = stubsByPackage.computeIfAbsent(pkg, k -> new ArrayList<>());
		int index = replaced == null ? -1 : stubs.indexOf(replaced);
		if (index >= 0) {
			stubs.set(index, stub);
		} else {
			stubs.add(stub);
		}
		String generated;
		try {
			generated = compile(pkg, routine);
		} catch (RuntimeException e) {
			stubs.remove(stub);
			throw e;
		}
		MockLog.stubbed(MockLog.call(routine.qualifiedName(), stub.invocation().matchers()), stub.action(), start);
		MockLog.generated(generated);
		return stub;
	}

	/**
	 * {@code stub}, with the object type or collection it returns, and those it sets as OUT values, written as PL/SQL
	 * constructors. The core encodes each value as a call would send it, so the mapping is the one the application
	 * uses, {@code @PlsqlType}, {@code @PlsqlAttr} and converters included, and the constructor is written from what
	 * the driver holds. A RECORD, a {@code %ROWTYPE} and an {@code INDEX BY} have no constructor, and are refused.
	 */
	private Stub written(Stub stub, RoutineMetadata routine) {
		Invocation invocation = stub.invocation();
		Stub.Action action = stub.action();
		boolean returnsComposite = action instanceof Stub.Return returned && returned.function()
				&& returned.value() != null && BodyRewriter.composite(routine.returnValue().orElseThrow());
		List<Matcher> matchers = new ArrayList<>(invocation.matchers());
		Map<Integer, RoutineMetadata.Argument> outs = new LinkedHashMap<>();
		for (int i = 0; i < matchers.size(); i++) {
			RoutineMetadata.Argument parameter = parameterAt(routine, invocation.method(), i);
			if (matchers.get(i) instanceof Matcher.Out out && out.value() != null && BodyRewriter.composite(parameter)) {
				outs.put(i, parameter);
			}
		}
		if (!returnsComposite && outs.isEmpty()) {
			return stub;
		}
		Object describer = describerOf(invocation.mock().type());
		try (Connection connection = connection()) {
			if (returnsComposite) {
				action = new Stub.Return(write(describer, invocation.method(), routine,
						routine.returnValue().orElseThrow(), ((Stub.Return) action).value(), connection), true);
			}
			for (Map.Entry<Integer, RoutineMetadata.Argument> out : outs.entrySet()) {
				matchers.set(out.getKey(), new Matcher.Out(write(describer, invocation.method(), routine,
						out.getValue(), ((Matcher.Out) matchers.get(out.getKey())).value(), connection)));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to write the stub of " + routine.qualifiedName() + " as PL/SQL: "
					+ e.getMessage(), e);
		}
		return new Stub(new Invocation(invocation.mock(), invocation.method(), List.copyOf(matchers)), action);
	}

	private static PlsqlLiterals.Written write(Object describer, Method method, RoutineMetadata routine,
			RoutineMetadata.Argument target, Object value, Connection connection) throws SQLException {
		String what = target.name() == null ? "the value it returns" : target.name();
		if ("PL/SQL RECORD".equals(target.dataType())) {
			throw new IllegalStateException(routine.qualifiedName() + ": " + what + " is a RECORD or a %ROWTYPE, which"
					+ " the mocks cannot write yet, since PL/SQL has no constructor for it: they write object types and"
					+ " collections; stub it with thenReturn(null) or thenThrow");
		}
		if ("PL/SQL TABLE".equals(target.dataType())) {
			throw new IllegalStateException(routine.qualifiedName() + ": " + what + " is an INDEX BY collection, which"
					+ " has no constructor, so the mocks cannot write it: they write nested tables and VARRAYs");
		}
		return PlsqlLiterals.constructor(Views.handlerOf(describer).encode(method, target, value, connection));
	}

	/** The parameter the {@code index}-th Java argument binds to, as the call would bind it. */
	private static RoutineMetadata.Argument parameterAt(RoutineMetadata routine, Method method, int index) {
		if (!routine.byName()) {
			return routine.parameters().get(index);
		}
		String name = RoutineMetadata.oracleNameOf(method, method.getParameters()[index]);
		return routine.parameters().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
	}

	/** Compiles the package's body with its stubs, and gives the PL/SQL generated for {@code any}'s routine. */
	private String compile(String pkg, RoutineMetadata any) {
		String body = bodies.computeIfAbsent(pkg, k -> source(any));
		Map<String, BodyRewriter.Subprogram> subprograms = new LinkedHashMap<>();
		for (Stub stub : stubsByPackage.get(pkg)) {
			RoutineMetadata routine = routineOf(stub.invocation());
			subprograms.computeIfAbsent(BodyRewriter.callKey(routine),
					k -> new BodyRewriter.Subprogram(routine, new ArrayList<>()))
					.stubs().add(new BodyRewriter.StubbedCall(byParameter(routine, stub.invocation()), stub.action()));
		}
		Map<String, String> generated = new LinkedHashMap<>();
		String mocked = BodyRewriter.rewrite(pkg, body, List.copyOf(subprograms.values()), generated);
		List<String> errors;
		try (Connection connection = connection()) {
			errors = compileInEdition(connection, mocked, any.schema(), any.packageName(), "PACKAGE BODY");
		} catch (SQLException e) {
			throw new IllegalStateException("failed to compile the mocked body of " + pkg + ": " + e.getMessage(), e);
		}
		if (!errors.isEmpty()) {
			throw new IllegalStateException(undeclaredRaise(pkg, errors) + "the mocked body of " + pkg
					+ " does not compile:\n  " + String.join("\n  ", errors) + "\n" + mocked);
		}
		return generated.get(BodyRewriter.callKey(any));
	}

	/**
	 * The likely mistake, when the errors name an exception a stub raises: the dictionary does not list declared
	 * exceptions, so a wrong name shows up only here, as {@code PLS-00302} for an exception the package does not
	 * declare, or {@code PLS-00201} for a package that does not exist.
	 */
	private String undeclaredRaise(String pkg, List<String> errors) {
		for (Stub stub : stubsByPackage.get(pkg)) {
			if (stub.action() instanceof Stub.Raise raise && raise.name().contains(".")
					&& errors.stream().map(error -> error.toUpperCase(Locale.ROOT)).anyMatch(error ->
							error.contains(raise.name()) || error.contains("'"
									+ raise.name().substring(raise.name().lastIndexOf('.') + 1) + "'"))) {
				return raise.name() + ", which a stub raises, is not declared: check that the package declares it in"
						+ " its spec, by this name. ";
			}
		}
		return "";
	}

	/** Runs {@code ddl} in the test's edition, and gives the compilation errors of the object it creates. */
	private List<String> compileInEdition(Connection connection, String ddl, String owner, String name, String type)
			throws SQLException {
		try {
			EditionDataSource.execute(connection, "ALTER SESSION SET EDITION = " + edition);
			EditionDataSource.execute(connection, ddl);
			return strings(connection, "SELECT LINE || ': ' || TEXT FROM ALL_ERRORS WHERE OWNER = '" + owner
					+ "' AND NAME = '" + name + "' AND TYPE = '" + type + "' ORDER BY SEQUENCE");
		} finally {
			EditionDataSource.execute(connection, "ALTER SESSION SET EDITION = " + defaultEdition);
		}
	}

	// ---------------------------------------------------------------- scripts

	/**
	 * Applies {@code script}, from {@code source}, in the test's edition, on a connection of the mocks' own: every
	 * statement is checked first, so a refused one applies none; then each runs, and a compilation error stops the
	 * script there, with the statements before it applied, until the edition goes.
	 */
	public void applyScript(String script, String source) {
		requireBegun();
		List<PlsqlScript.Statement> statements = PlsqlScript.parse(script, source);
		EditionScripts.requireAccepted(statements);
		try (Connection connection = connection()) {
			EditionScripts.requireEditioned(statements, schema, connection);
			for (PlsqlScript.Statement statement : statements) {
				requireNoStubs(statement);
			}
			for (PlsqlScript.Statement statement : statements) {
				long start = System.nanoTime();
				List<String> errors = compileInEdition(connection, statement.sql(), schema, statement.name(),
						statement.type());
				if (!errors.isEmpty()) {
					throw new IllegalStateException(statement.where() + ": " + statement.object() + " does not compile:\n  "
							+ String.join("\n  ", errors) + "\n" + statement.sql());
				}
				if (statement.type().startsWith("PACKAGE")) {
					scriptedPackages.add(schema + "." + statement.name());
				}
				MockLog.applied(statement, start);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to apply " + source + ": " + e.getMessage(), e);
		}
	}

	/** A script replacing a package with stubs would undo them, and the stubs would count nothing. */
	private void requireNoStubs(PlsqlScript.Statement statement) {
		String pkg = schema + "." + statement.name();
		if (statement.type().startsWith("PACKAGE") && stubsByPackage.containsKey(pkg)) {
			throw new IllegalStateException(statement.where() + ": " + pkg + " has stubs in this test, and replacing it"
					+ " with a script would undo them: a package has stubs or is replaced by a script in a test, not both");
		}
	}

	/** A stub rewrites the body as the default edition has it, which would undo what a script replaced. */
	private void requireNotScripted(RoutineMetadata routine) {
		String pkg = packageOf(routine);
		if (scriptedPackages.contains(pkg)) {
			throw new IllegalStateException(routine.qualifiedName() + ": " + pkg + " was replaced by a script in this"
					+ " test, and a stub would undo it, since it rewrites the body as the default edition has it: a"
					+ " package has stubs or is replaced by a script in a test, not both");
		}
	}

	// ---------------------------------------------------------------- %ROWTYPE records

	/**
	 * In a child edition, the driver cannot resolve {@code "SCHEMA".TABLE%ROWTYPE} and fails with {@code ORA-17060},
	 * while it resolves a package subtype of it. So the first time a record of a table is bound in the test's edition,
	 * a package with that subtype is compiled in the edition, and the record goes by the subtype's name from then on;
	 * the package goes with the edition. It is compiled in the schema of the mocks, whose packages have editions, and
	 * never in the table's, which may have none, and where nothing is written: the subtype takes the grant on the
	 * table that the routine taking the record already needs. Its name carries the edition's, so that no connection
	 * of the pool can hold the descriptor of the one of an earlier test, and a number, in the order the test binds
	 * the tables. The name comes from the core as the dictionary has it, whichever way the code wrote it, so one
	 * table is one name here.
	 */
	@Override
	public String nameOf(CompositeType type, String name) {
		String current = edition;
		if (current == null) {
			return name;
		}
		synchronized (rowTypes) {
			String known = rowTypes.get(name);
			if (known != null) {
				return known;
			}
			long start = System.nanoTime();
			// numbered as the tables come: no two tables of a test share a package, and none is compiled twice
			String pkg = "PENITENT_ROW_" + current.substring(PREFIX.length()) + "_" + (rowTypes.size() + 1);
			String ddl = "CREATE OR REPLACE PACKAGE \"" + schema + "\"." + pkg + " AS\n  SUBTYPE ROW_T IS "
					+ name + ";\nEND " + pkg + ";";
			String alias;
			try (Connection connection = connection()) {
				List<String> errors = compileInEdition(connection, ddl, schema, pkg, "PACKAGE");
				if (!errors.isEmpty()) {
					throw new IllegalStateException("the subtype of " + name + " for the test's edition does not"
							+ " compile:\n  " + String.join("\n  ", errors) + "\n" + ddl);
				}
				alias = schema + "." + pkg + ".ROW_T";
			} catch (SQLException e) {
				throw new IllegalStateException("failed to compile the subtype of " + name + " for the test's edition: "
						+ e.getMessage(), e);
			}
			rowTypes.put(name, alias);
			MockLog.aliased(name, alias, start);
			MockLog.generated(ddl);
			return alias;
		}
	}

	/** The body as the database has it, in the default edition. */
	private String source(RoutineMetadata routine) {
		String pkg = packageOf(routine);
		try (Connection connection = connection();
				PreparedStatement select = connection.prepareStatement("SELECT TEXT FROM ALL_SOURCE WHERE OWNER = ?"
						+ " AND NAME = ? AND TYPE = 'PACKAGE BODY' ORDER BY LINE")) {
			select.setString(1, routine.schema());
			select.setString(2, routine.packageName());
			StringBuilder text = new StringBuilder();
			try (ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					text.append(rows.getString(1));
				}
			}
			if (text.isEmpty()) {
				throw new IllegalStateException(pkg + " has no package body to mock, or it cannot be read from ALL_SOURCE");
			}
			String firstLine = text.toString().lines().findFirst().orElse("").toUpperCase(Locale.ROOT);
			if (firstLine.matches(".*\\bWRAPPED\\s*$")) {
				throw new IllegalStateException(pkg + " has a wrapped body, which cannot be parsed, so it cannot be mocked");
			}
			return "CREATE OR REPLACE " + text;
		} catch (SQLException e) {
			throw new IllegalStateException("failed to read the body of " + pkg + ": " + e.getMessage(), e);
		}
	}

	// ---------------------------------------------------------------- verification

	public void verify(Invocation invocation, int times) {
		RoutineMetadata routine = routineOf(invocation);
		requireStubbed(routine);
		Map<String, Matcher> byParameter = byParameter(routine, invocation);
		requireMatchable(routine, byParameter);
		StringBuilder sql = new StringBuilder(
				"SELECT COUNT(*) FROM PENITENT_MOCK_CALLS WHERE EDITION_NAME = ? AND ROUTINE = ?");
		int index = 0;
		for (RoutineMetadata.Argument parameter : routine.parameters()) {
			if (!parameter.isIn()) {
				continue;
			}
			Matcher matcher = byParameter.get(parameter.name());
			String check = matcher == null ? null
					: matcher.condition("JSON_VALUE(ARGS, '$[" + index + "]' RETURNING " + jsonType(parameter) + ")");
			if (check != null) {
				sql.append(" AND ").append(check);
			}
			index++;
		}
		int calls;
		try (Connection connection = connection();
				PreparedStatement count = connection.prepareStatement(sql.toString())) {
			count.setString(1, edition);
			count.setString(2, BodyRewriter.callKey(routine));
			try (ResultSet rows = count.executeQuery()) {
				rows.next();
				calls = rows.getInt(1);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to read the calls of " + routine.qualifiedName() + ": "
					+ e.getMessage(), e);
		}
		if (calls == times) {
			MockLog.verified(MockLog.call(routine.qualifiedName(), invocation.matchers()), times);
			return;
		}
		throw new AssertionError(routine.qualifiedName() + invocation.matchers().toString().replace('[', '(')
				.replace(']', ')') + " was expected " + timesOf(times) + " in this test, and was called "
				+ timesOf(calls));
	}

	/**
	 * An argument of an object type, a collection or a record is matched with {@code any()} only, so far: no PL/SQL
	 * literal stands for its value, and a recorded call does not hold it. Anything else is refused now, rather than
	 * failing to compile, or counting calls wrong. An OUT value of one is set, not matched: see {@link #written}.
	 */
	private static void requireMatchable(RoutineMetadata routine, Map<String, Matcher> byParameter) {
		for (RoutineMetadata.Argument parameter : routine.parameters()) {
			Matcher matcher = byParameter.get(parameter.name());
			if (matcher == null || matcher instanceof Matcher.Any || matcher instanceof Matcher.Out
					|| !BodyRewriter.composite(parameter)) {
				continue;
			}
			throw new IllegalStateException(routine.qualifiedName() + ": " + parameter.name() + " is of "
					+ parameter.dataType() + ", which the mocks match with any() only, so far: write any() for it,"
					+ " and match the other arguments by value");
		}
	}

	/**
	 * Only a stubbed routine records its calls: one that is not runs its real code, which records nothing, so a
	 * count of its calls would always be zero, and a {@code never()} would pass whatever happened.
	 */
	private void requireStubbed(RoutineMetadata routine) {
		String key = BodyRewriter.callKey(routine);
		boolean stubbed = stubsByPackage.getOrDefault(packageOf(routine), List.of()).stream()
				.anyMatch(stub -> BodyRewriter.callKey(routineOf(stub.invocation())).equals(key));
		if (!stubbed) {
			throw new IllegalStateException(routine.qualifiedName() + " has no stub in this test, so its calls are not"
					+ " recorded: only a stubbed routine records them, and verify can only count those");
		}
	}

	private static String jsonType(RoutineMetadata.Argument parameter) {
		String type = parameter.dataType();
		if (type.equals("DATE")) {
			return "DATE";
		}
		if (type.startsWith("TIMESTAMP")) {
			return "TIMESTAMP";
		}
		if (parameter.isNumeric()) {
			return "NUMBER";
		}
		return "VARCHAR2(4000)";
	}

	private static String timesOf(int count) {
		return count == 1 ? "1 time" : count + " times";
	}

	// ---------------------------------------------------------------- routines

	private RoutineMetadata routineOf(Invocation invocation) {
		Object describer = describerOf(invocation.mock().type());
		try {
			return Views.handlerOf(describer).describe(invocation.method());
		} catch (RuntimeException e) {
			if (noConnectionToSpare(e)) {
				throw noConnection(e);
			}
			throw e;
		}
	}

	/**
	 * A connection of the mocks' own, outside any transaction of the test: DDL commits. The pool has to have one to
	 * spare besides those the test holds, or the mocks wait for one until the pool gives up.
	 */
	private Connection connection() throws SQLException {
		try {
			return base.getConnection();
		} catch (SQLException e) {
			if (noConnectionToSpare(e)) {
				throw noConnection(e);
			}
			throw e;
		}
	}

	private static boolean noConnectionToSpare(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof java.sql.SQLTransientConnectionException) {
				return true;
			}
		}
		return false;
	}

	private static IllegalStateException noConnection(Throwable cause) {
		return new IllegalStateException("the mocks need a connection of their own, besides those the test holds, and"
				+ " the pool had none to spare: give the pool at least one connection more than the test uses at once",
				cause);
	}

	private Object describerOf(Class<?> type) {
		return describers.computeIfAbsent(type, t -> PlsqlPackages.create(t, base));
	}

	/** The matcher of each parameter of the routine, by its Oracle name, as the call would bind the arguments. */
	private static Map<String, Matcher> byParameter(RoutineMetadata routine, Invocation invocation) {
		Method method = invocation.method();
		Parameter[] javaParameters = method.getParameters();
		List<RoutineMetadata.Argument> parameters = routine.parameters();
		Map<String, Matcher> byParameter = new HashMap<>();
		for (int i = 0; i < javaParameters.length; i++) {
			String name = routine.byName() ? RoutineMetadata.oracleNameOf(method, javaParameters[i])
					: parameters.get(i).name();
			byParameter.put(name, invocation.matchers().get(i));
		}
		return byParameter;
	}

	private static String packageOf(RoutineMetadata routine) {
		if (routine.packageName() == null) {
			throw new IllegalStateException(routine.qualifiedName() + " is not in a package: the mocks only rewrite"
					+ " routines of packages yet");
		}
		return routine.schema() + "." + routine.packageName();
	}

	private void requireBegun() {
		if (edition == null) {
			throw new IllegalStateException("the mocks have not begun: call begin() first");
		}
	}

	private static String single(Connection connection, String sql) throws SQLException {
		List<String> values = strings(connection, sql);
		return values.isEmpty() ? null : values.get(0);
	}

	private static List<String> strings(Connection connection, String sql) throws SQLException {
		List<String> values = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
			while (rows.next()) {
				values.add(rows.getString(1));
			}
		}
		return values;
	}
}
