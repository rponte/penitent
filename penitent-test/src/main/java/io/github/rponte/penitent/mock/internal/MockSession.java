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
import java.util.StringJoiner;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.internal.metadata.CompositeType;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata;
import io.github.rponte.penitent.internal.proxy.RoutineInvocationHandler;
import io.github.rponte.penitent.internal.proxy.Views;

/**
 * One test's mocks: its edition, the stubs of each package, compiled into the edition as they come, the subtypes its
 * {@code %ROWTYPE} records go by there, and the calls recorded, for {@code verify}. Everything that is DDL runs on a
 * connection of its own, outside any transaction of the test, since DDL commits.
 */
public final class MockSession implements RowTypes {

	/** The version of {@code penitent-mock-schema.sql} this library expects. */
	static final int SCHEMA_VERSION = 2;

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
			throw new IllegalStateException("failed to drop the test's edition " + ending + (TRANSIENT.contains(
					e.getErrorCode()) ? ", in " + DROP_ATTEMPTS + " attempts" : " (a connection taken in the test may still"
							+ " be open, and in it)") + ": " + e.getMessage(), e);
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

	/**
	 * Errors that dropping an edition gets past when run again: a deadlock, ORA-00060, which Oracle rolls back and
	 * expects to be run again; and ORA-00604 over ORA-04043, an object of the edition gone during the drop's recursive
	 * SQL. Oracle 19c on the Autonomous Database showed both, now and then, in the CI.
	 */
	private static final java.util.Set<Integer> TRANSIENT = java.util.Set.of(60, 604, 4043);

	private static final int DROP_ATTEMPTS = 3;

	/**
	 * Drops {@code edition}, its objects and its recorded calls. A transient error is run past, up to
	 * {@value #DROP_ATTEMPTS} attempts, each one logged; any other fails at once.
	 */
	private static void drop(Connection connection, String edition) throws SQLException {
		for (int attempt = 1;; attempt++) {
			try {
				EditionDataSource.execute(connection, "DROP EDITION " + edition + " CASCADE");
				break;
			} catch (SQLException e) {
				if (attempt == DROP_ATTEMPTS || !TRANSIENT.contains(e.getErrorCode())) {
					throw e;
				}
				MockLog.retriedDrop(edition, attempt, DROP_ATTEMPTS, e);
				pause(attempt);
			}
		}
		try (PreparedStatement delete = connection.prepareStatement(
				"DELETE FROM PENITENT_MOCK_CALLS WHERE EDITION_NAME = ?")) {
			delete.setString(1, edition);
			delete.executeUpdate();
		}
		if (!connection.getAutoCommit()) {
			connection.commit();
		}
	}

	/** A short wait before trying again, longer each time, so that what held the edition can finish. */
	private static void pause(int attempt) {
		try {
			Thread.sleep(500L * attempt);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
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
	 * {@code stub}, with the value it returns, those it sets as OUT values and those its arguments are matched with
	 * written as PL/SQL. The core encodes each value as a call would send it, through the method's or the parameter's
	 * converter and {@code @PlsqlBoolean}, so the mapping is the one the application uses: an enum by its name, a UUID
	 * as text, a boolean by its literals, {@code @PlsqlType} and {@code @PlsqlAttr} for a composite; and the PL/SQL is
	 * written from what it encoded. A RECORD, a {@code %ROWTYPE} and an {@code INDEX BY} have no constructor, and are
	 * written as qualified expressions, as {@link PlsqlLiterals#constructor} says.
	 */
	private Stub written(Stub stub, RoutineMetadata routine) {
		Invocation invocation = stub.invocation();
		Stub.Action action = stub.action();
		boolean returns = action instanceof Stub.Return returned && returned.function() && returned.value() != null;
		List<Matcher> matchers = new ArrayList<>(invocation.matchers());
		List<Integer> outs = new ArrayList<>();
		List<Integer> equals = new ArrayList<>();
		for (int i = 0; i < matchers.size(); i++) {
			if (matchers.get(i) instanceof Matcher.Out out && out.value() != null) {
				outs.add(i);
			} else if (matchers.get(i) instanceof Matcher.Equal equal && equal.value() != null) {
				equals.add(i);
			}
		}
		if (!returns && outs.isEmpty() && equals.isEmpty()) {
			return stub;
		}
		RoutineInvocationHandler handler = Views.handlerOf(describerOf(invocation.mock().type()));
		Method method = invocation.method();
		try (Connection connection = connection()) {
			if (returns) {
				action = new Stub.Return(literal(handler.encodeReturn(method, ((Stub.Return) action).value(),
						connection), connection), true);
			}
			for (int i : outs) {
				matchers.set(i, new Matcher.Out(literal(handler.encodeParameter(method, i,
						((Matcher.Out) matchers.get(i)).value(), connection), connection)));
			}
			for (int i : equals) {
				matchers.set(i, new Matcher.Equal(literal(handler.encodeParameter(method, i,
						((Matcher.Equal) matchers.get(i)).value(), connection), connection)));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to write the stub of " + routine.qualifiedName() + " as PL/SQL: "
					+ e.getMessage(), e);
		}
		return new Stub(new Invocation(invocation.mock(), method, List.copyOf(matchers)), action);
	}

	/** A value the core encoded, written as PL/SQL. */
	private PlsqlLiterals.Written literal(Object encoded, Connection connection) throws SQLException {
		return PlsqlLiterals.constructor(encoded, driverName -> markOf(driverName, connection));
	}

	/**
	 * How PL/SQL writes a value of the type the driver names {@code driverName}, from the dictionary: a type of a
	 * package, a RECORD or a collection, by its name, with named indexes if it is an {@code INDEX BY}; an object type or
	 * a collection of the schema, by its name; and a table, which the driver names for its {@code %ROWTYPE}, by the
	 * RECORD of its columns that {@link #rowPackage} declares, since a {@code %ROWTYPE} has no qualified expression and
	 * Oracle assigns that RECORD to it. A table and a type of one schema cannot share a name, so this is not ambiguous.
	 */
	private PlsqlLiterals.Mark markOf(String driverName, Connection connection) throws SQLException {
		List<String> parts = PlsqlLiterals.parts(driverName);
		if (parts.size() == 3) {
			try (PreparedStatement select = connection.prepareStatement("SELECT INDEX_BY FROM ALL_PLSQL_COLL_TYPES"
					+ " WHERE OWNER = ? AND PACKAGE_NAME = ? AND TYPE_NAME = ?")) {
				for (int i = 0; i < 3; i++) {
					select.setString(i + 1, parts.get(i));
				}
				try (ResultSet rows = select.executeQuery()) {
					return new PlsqlLiterals.Mark(PlsqlLiterals.quoted(parts), rows.next() && rows.getString(1) != null);
				}
			}
		}
		if (parts.size() == 2) {
			try (PreparedStatement select = connection.prepareStatement("SELECT 1 FROM ALL_TYPES WHERE OWNER = ? AND"
					+ " TYPE_NAME = ?")) {
				select.setString(1, parts.get(0));
				select.setString(2, parts.get(1));
				try (ResultSet rows = select.executeQuery()) {
					if (!rows.next()) {
						String row = new CompositeType(CompositeType.Kind.ROWTYPE, parts.get(0), null, parts.get(1),
								parts.get(1) + "%ROWTYPE").qualifiedBindName();
						return new PlsqlLiterals.Mark("\"" + schema + "\"." + rowPackage(row, parts.get(0), parts.get(1))
								+ ".LITERAL_T", false);
					}
				}
			}
		}
		return new PlsqlLiterals.Mark(PlsqlLiterals.quoted(parts), false);
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
			requireEditioned(any, connection);
			errors = compileInEdition(connection, mocked, any.schema(), unitName(any), unitType(any));
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
				if (rewritable(statement)) {
					scriptedPackages.add(schema + "." + statement.name());
				}
				MockLog.applied(statement, start);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("failed to apply " + source + ": " + e.getMessage(), e);
		}
	}

	/** What a stub rewrites too: a package, or a standalone function or procedure. */
	private static boolean rewritable(PlsqlScript.Statement statement) {
		return statement.type().startsWith("PACKAGE") || statement.type().equals("FUNCTION")
				|| statement.type().equals("PROCEDURE");
	}

	/** A script replacing a package with stubs would undo them, and the stubs would count nothing. */
	private void requireNoStubs(PlsqlScript.Statement statement) {
		String pkg = schema + "." + statement.name();
		if (rewritable(statement) && stubsByPackage.containsKey(pkg)) {
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
	 * a package with that subtype is compiled in the edition, by {@link #rowPackage}, and the record goes by the
	 * subtype's name from then on; the package goes with the edition. It is compiled in the schema of the mocks, whose packages have editions, and
	 * never in the table's, which may have none, and where nothing is written: the subtype takes the grant on the
	 * table that the routine taking the record already needs. Its name carries the edition's, so that no connection
	 * of the pool can hold the descriptor of the one of an earlier test, and a number, in the order the test binds
	 * the tables. The name comes from the core as the dictionary has it, whichever way the code wrote it, so one
	 * table is one name here.
	 */
	@Override
	public String nameOf(CompositeType type, String name) {
		if (edition == null) {
			return name;
		}
		return schema + "." + rowPackage(name, type.schema(), type.simpleName()) + ".ROW_T";
	}

	/**
	 * The package of the table {@code owner.table}, whose {@code %ROWTYPE} is {@code name}, compiled in the test's
	 * edition the first time a test needs it: {@code ROW_T}, the subtype the driver resolves, and {@code LITERAL_T}, a
	 * RECORD of the table's columns, each with its column's type, which a stub writes a {@code %ROWTYPE} as.
	 */
	private String rowPackage(String name, String owner, String table) {
		String current = edition;
		synchronized (rowTypes) {
			String known = rowTypes.get(name);
			if (known != null) {
				return known;
			}
			long start = System.nanoTime();
			// numbered as the tables come: no two tables of a test share a package, and none is compiled twice
			String pkg = "PENITENT_ROW_" + current.substring(PREFIX.length()) + "_" + (rowTypes.size() + 1);
			try (Connection connection = connection()) {
				String ddl = "CREATE OR REPLACE PACKAGE \"" + schema + "\"." + pkg + " AS\n  SUBTYPE ROW_T IS "
						+ name + ";\n  TYPE LITERAL_T IS RECORD (" + columnsOf(connection, owner, table) + ");\nEND "
						+ pkg + ";";
				List<String> errors = compileInEdition(connection, ddl, schema, pkg, "PACKAGE");
				if (!errors.isEmpty()) {
					throw new IllegalStateException("the subtype of " + name + " for the test's edition does not"
							+ " compile:\n  " + String.join("\n  ", errors) + "\n" + ddl);
				}
				rowTypes.put(name, pkg);
				MockLog.aliased(name, schema + "." + pkg + ".ROW_T", start);
				MockLog.generated(ddl);
				return pkg;
			} catch (SQLException e) {
				throw new IllegalStateException("failed to compile the subtype of " + name + " for the test's edition: "
						+ e.getMessage(), e);
			}
		}
	}

	/** The fields of {@code LITERAL_T}: the columns a {@code %ROWTYPE} of the table has, in its order, typed as they are. */
	private static String columnsOf(Connection connection, String owner, String table) throws SQLException {
		StringJoiner columns = new StringJoiner(", ");
		try (PreparedStatement select = connection.prepareStatement("SELECT COLUMN_NAME FROM ALL_TAB_COLUMNS WHERE"
				+ " OWNER = ? AND TABLE_NAME = ? AND COLUMN_ID IS NOT NULL ORDER BY COLUMN_ID")) {
			select.setString(1, owner);
			select.setString(2, table);
			try (ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					String column = "\"" + rows.getString(1) + "\"";
					columns.add(column + " \"" + owner + "\".\"" + table + "\"." + column + "%TYPE");
				}
			}
		}
		return columns.toString();
	}

	/** The package body, or the standalone routine, as the database has it, in the default edition. */
	private String source(RoutineMetadata routine) {
		String pkg = packageOf(routine);
		try (Connection connection = connection();
				PreparedStatement select = connection.prepareStatement("SELECT TEXT FROM ALL_SOURCE WHERE OWNER = ?"
						+ " AND NAME = ? AND TYPE = ? ORDER BY LINE")) {
			select.setString(1, routine.schema());
			select.setString(2, unitName(routine));
			select.setString(3, unitType(routine));
			StringBuilder text = new StringBuilder();
			try (ResultSet rows = select.executeQuery()) {
				while (rows.next()) {
					text.append(rows.getString(1));
				}
			}
			if (text.isEmpty()) {
				throw new IllegalStateException(pkg + " has no " + unitType(routine).toLowerCase(Locale.ROOT)
						+ " to mock, or it cannot be read from ALL_SOURCE");
			}
			String firstLine = text.toString().lines().findFirst().orElse("").toUpperCase(Locale.ROOT);
			if (firstLine.matches(".*\\bWRAPPED\\s*$")) {
				throw new IllegalStateException(pkg + " has a wrapped source, which cannot be parsed, so it cannot be"
						+ " mocked");
			}
			return "CREATE OR REPLACE " + text;
		} catch (SQLException e) {
			throw new IllegalStateException("failed to read the body of " + pkg + ": " + e.getMessage(), e);
		}
	}

	// ---------------------------------------------------------------- verification

	/**
	 * Counts the recorded calls that match {@code invocation}. A value matched is encoded as a call would send it, as
	 * in a stub, and bound to the query rather than written in it: each recorded argument is read back with its own
	 * type, and text, a LOB and bytes, which the recording keeps as text and hex digits, are compared as a
	 * {@code CLOB}, which no length overflows.
	 */
	public void verify(Invocation invocation, int times) {
		RoutineMetadata routine = routineOf(invocation);
		requireStubbed(routine);
		Map<String, Matcher> byParameter = byParameter(routine, invocation);
		requireMatchable(routine, byParameter);
		Map<String, Integer> javaIndexes = javaIndexes(routine, invocation);
		RoutineInvocationHandler handler = Views.handlerOf(describerOf(invocation.mock().type()));
		StringBuilder sql = new StringBuilder(
				"SELECT COUNT(*) FROM PENITENT_MOCK_CALLS WHERE EDITION_NAME = ? AND ROUTINE = ?");
		List<Expected> binds = new ArrayList<>();
		int calls;
		try (Connection connection = connection()) {
			int index = 0;
			for (RoutineMetadata.Argument parameter : routine.parameters()) {
				if (!parameter.isIn()) {
					continue;
				}
				Matcher matcher = byParameter.get(parameter.name());
				String recorded = "JSON_VALUE(ARGS, '$[" + index + "]' RETURNING " + jsonType(parameter) + ")";
				if (matcher instanceof Matcher.Equal equal && equal.value() != null) {
					Expected expected = expected(parameter, parameter.isBoolean() ? equal.value()
							: handler.encodeParameter(invocation.method(), javaIndexes.get(parameter.name()),
									equal.value(), connection));
					sql.append(" AND ").append(expected.text() ? "DBMS_LOB.COMPARE(" + recorded + ", ?) = 0"
							: recorded + " = ?");
					binds.add(expected);
				} else {
					String check = matcher == null ? null : matcher.condition(recorded);
					if (check != null) {
						sql.append(" AND ").append(check);
					}
				}
				index++;
			}
			try (PreparedStatement count = connection.prepareStatement(sql.toString())) {
				count.setString(1, edition);
				count.setString(2, BodyRewriter.callKey(routine));
				for (int i = 0; i < binds.size(); i++) {
					binds.get(i).bind(count, i + 3);
				}
				try (ResultSet rows = count.executeQuery()) {
					rows.next();
					calls = rows.getInt(1);
				}
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

	/** A value a verification binds: as text, which a CLOB is compared with, or as the driver binds it. */
	private record Expected(Object value, boolean text, String dataType) {

		void bind(PreparedStatement statement, int position) throws SQLException {
			if (text) {
				String string = (String) value;
				statement.setClob(position, new java.io.StringReader(string), string.length());
			} else if (value instanceof Double number && dataType.equals("BINARY_DOUBLE")) {
				statement.unwrap(oracle.jdbc.OraclePreparedStatement.class).setBinaryDouble(position, number);
			} else if (value instanceof Float number && dataType.equals("BINARY_FLOAT")) {
				statement.unwrap(oracle.jdbc.OraclePreparedStatement.class).setBinaryFloat(position, number);
			} else {
				statement.setObject(position, value);
			}
		}
	}

	/**
	 * The value a recorded argument is compared with: a BOOLEAN is recorded as 'true' or 'false', since only Oracle
	 * 23ai takes a BOOLEAN into SQL; bytes, of a RAW or a BLOB, as their hex digits, as JSON_ARRAY writes them; and a
	 * text, of any length, as itself.
	 */
	private static Expected expected(RoutineMetadata.Argument parameter, Object encoded) throws SQLException {
		if (parameter.isBoolean()) {
			return new Expected(Boolean.TRUE.equals(encoded) ? "true" : "false", true, parameter.dataType());
		}
		Object value = encoded;
		if (value instanceof java.sql.Clob clob) {
			value = clob.getSubString(1, Math.toIntExact(clob.length()));
		} else if (value instanceof java.sql.Blob blob) {
			value = blob.getBytes(1, Math.toIntExact(blob.length()));
		}
		if (value instanceof byte[] bytes) {
			value = java.util.HexFormat.of().withUpperCase().formatHex(bytes);
		}
		boolean text = jsonType(parameter).equals("CLOB");
		return new Expected(text ? String.valueOf(value) : value, text, parameter.dataType());
	}

	/**
	 * The type a recorded argument is read back with: its own, for numbers, dates, timestamps and intervals, a
	 * {@code DATE} with {@code PRESERVE TIME}, since {@code RETURNING DATE} truncates the time by default, as Oracle
	 * documents and 19c, 21c and 23ai do; a {@code TIMESTAMP WITH LOCAL TIME ZONE} as {@code WITH TIME ZONE}, which
	 * {@code RETURNING} takes; and a {@code CLOB} for text, LOBs, bytes and booleans, which the recording keeps as text.
	 */
	private static String jsonType(RoutineMetadata.Argument parameter) {
		String type = parameter.dataType();
		if (type.equals("DATE")) {
			return "DATE PRESERVE TIME";
		}
		if (type.startsWith("TIMESTAMP")) {
			return type.contains("TIME ZONE") ? "TIMESTAMP WITH TIME ZONE" : "TIMESTAMP";
		}
		if (type.startsWith("INTERVAL DAY")) {
			return "INTERVAL DAY TO SECOND";
		}
		if (type.startsWith("INTERVAL YEAR")) {
			return "INTERVAL YEAR TO MONTH";
		}
		if (type.equals("BINARY_DOUBLE") || type.equals("BINARY_FLOAT")) {
			return type;
		}
		if (parameter.isNumeric()) {
			return "NUMBER";
		}
		return "CLOB";
	}

	/** The index of the Java parameter each Oracle parameter of {@code routine} binds, by its name. */
	private static Map<String, Integer> javaIndexes(RoutineMetadata routine, Invocation invocation) {
		Map<String, Integer> indexes = new HashMap<>();
		for (int i = 0; i < invocation.method().getParameterCount(); i++) {
			indexes.put(routine.parameters().get(i).name(), i);
		}
		return indexes;
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

	/**
	 * The unit the stub rewrites, as {@code SCHEMA.NAME}: the package of a routine of a package, or the routine
	 * itself, when it is standalone.
	 */
	private static String packageOf(RoutineMetadata routine) {
		return routine.schema() + "." + unitName(routine);
	}

	private static String unitName(RoutineMetadata routine) {
		return routine.packageName() != null ? routine.packageName() : routine.routineName();
	}

	/** {@code PACKAGE BODY}, or {@code FUNCTION} or {@code PROCEDURE} for a standalone routine. */
	private static String unitType(RoutineMetadata routine) {
		if (routine.packageName() != null) {
			return "PACKAGE BODY";
		}
		return routine.returnValue().isPresent() ? "FUNCTION" : "PROCEDURE";
	}

	/**
	 * A standalone routine is compiled into the edition only if its schema editions its type: otherwise the
	 * {@code CREATE OR REPLACE} would replace the real routine, for every session, for good. Packages are checked by
	 * {@code begin()}; standalone routines are not required there, so that a schema without them keeps working.
	 */
	private void requireEditioned(RoutineMetadata routine, Connection connection) throws SQLException {
		if (routine.packageName() != null) {
			return;
		}
		String type = unitType(routine);
		if (!routine.schema().equals(schema)) {
			throw new IllegalStateException(routine.qualifiedName() + " is a standalone " + type + " of the schema "
					+ routine.schema() + ", and the mocks stub standalone routines of the schema of the mocks only, "
					+ schema + ", whose editions can be checked");
		}
		if (!strings(connection, "SELECT OBJECT_TYPE FROM USER_EDITIONED_TYPES").contains(type)) {
			throw new IllegalStateException(routine.qualifiedName() + " is a standalone " + type + ", and " + schema
					+ " does not edition its " + type + " objects, so its stub would replace the real one for every"
					+ " session, for good. On a test database, a DBA can run ALTER USER " + schema
					+ " ENABLE EDITIONS FOR " + type + ", which cannot be undone, or apply penitent-mock-admin.sql again");
		}
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
