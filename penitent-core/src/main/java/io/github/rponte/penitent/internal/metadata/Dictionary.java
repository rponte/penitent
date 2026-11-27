package io.github.rponte.penitent.internal.metadata;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/**
 * Dictionary queries, on the connection of the call in progress.
 *
 * <p>Always on that one, never on another connection from the pool: holding one connection while
 * asking for a second can deadlock with a small pool, and the dictionary must be the one seen by the
 * same session, which has the same edition and the same {@code current_schema} as the call.
 */
public final class Dictionary {

	@FunctionalInterface
	private interface RowMapper<T> {
		T map(ResultSet rs, int rowNum) throws SQLException;
	}

	/**
	 * A database failure crossing code that cannot declare {@link SQLException}, such as the caches'
	 * {@code computeIfAbsent}. The handler translates it, at the edge of the call.
	 */
	public static final class SqlFailure extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final String sql;
		private final PlsqlFailure.Phase phase;

		public SqlFailure(String sql, SQLException cause, PlsqlFailure.Phase phase) {
			super(cause);
			this.sql = sql;
			this.phase = phase;
		}

		public PlsqlFailure failure() {
			return new PlsqlFailure(sql, (SQLException) getCause(), phase);
		}
	}

	/**
	 * The rows a dictionary query brings in each round trip (#169). The driver's default, 10, costs one more round
	 * trip for each further 10 rows, as an overloaded routine's parameters have. The rows are small, a few names and
	 * types, and a query's result lives only while it is read, so 100 costs little memory, and fixed: no setting,
	 * since nothing about the application changes what fits.
	 */
	private static final int FETCH_SIZE = 100;

	private final Connection connection;

	public Dictionary(Connection connection) {
		this.connection = connection;
	}

	private <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setFetchSize(FETCH_SIZE);
			for (int i = 0; i < parameters.length; i++) {
				statement.setObject(i + 1, parameters[i]);
			}
			try (ResultSet rs = statement.executeQuery()) {
				List<T> rows = new ArrayList<>();
				while (rs.next()) {
					rows.add(mapper.map(rs, rows.size()));
				}
				return rows;
			}
		} catch (SQLException e) {
			throw new SqlFailure(sql, e, PlsqlFailure.Phase.DICTIONARY);
		} catch (io.github.rponte.penitent.internal.driver.DriverFailure e) {
			throw e.ofCall("reading the dictionary", sql, PlsqlFailure.Phase.DICTIONARY);
		}
	}

	private List<String> strings(String sql, Object... parameters) {
		return query(sql, (rs, n) -> rs.getString(1), parameters);
	}

	/** For queries that always return one row, such as a {@code count(*)}. */
	private int count(String sql, Object... parameters) {
		return query(sql, (rs, n) -> rs.getInt(1), parameters).get(0);
	}

	public String currentSchema() {
		return strings("select sys_context('userenv', 'current_schema') from dual").get(0);
	}

	/**
	 * What {@code DBMS_UTILITY.NAME_RESOLVE} said of one name, as PL/SQL would resolve it ({@code context => 1}): the
	 * owner, {@code part1} for a package, {@code part2} for a standalone routine, the type of {@code part1} (7, a
	 * procedure; 8, a function; 9, a package), or the error of a name that does not resolve, {@code ORA-06564} for
	 * one, {@code 0} when it resolved. Any other error of Oracle's is raised, as a failure reading the dictionary.
	 */
	public record Resolution(int error, String message, String schema, String part1, String part2, String dblink,
			int type) {

		/** Whether the name resolved. */
		public boolean resolved() {
			return error == 0;
		}
	}

	/**
	 * Oracle's errors for a name that does not resolve: {@code ORA-06564}, not there or not accessible;
	 * {@code ORA-00980}, a cycle of synonyms; {@code ORA-04047}, not something PL/SQL calls, as a table or a type.
	 */
	private static final java.util.Set<Integer> NOT_RESOLVED = java.util.Set.of(6564, 980, 4047);

	/**
	 * Each of {@code names}, resolved by Oracle itself, in one round trip (#174): {@code DBMS_UTILITY.NAME_RESOLVE},
	 * which follows synonyms, of synonyms too, in Oracle's own order, in the session's {@code current_schema} and
	 * edition, with the caller's privileges. Each name in a block of its own, so that one that does not resolve does
	 * not stop the others; the answers come back as {@code OUT} binds, never as text to parse. The names go as
	 * PL/SQL would take them: quoted, to be taken as they are.
	 */
	public List<Resolution> resolve(List<String> names) {
		return resolve(names, PLSQL);
	}

	/** {@code NAME_RESOLVE}'s contexts: a table or a view, PL/SQL, a type. */
	private static final int TABLE = 0;
	private static final int PLSQL = 1;
	private static final int TYPE = 7;

	/** Each of {@code names}, resolved in {@code context}, in one round trip. */
	private List<Resolution> resolve(List<String> names, int context) {
		StringBuilder block = new StringBuilder("begin\n");
		for (int i = 0; i < names.size(); i++) {
			block.append("""
					  begin
					    dbms_utility.name_resolve(?, :context, ?, ?, ?, ?, ?, ?);
					  exception when others then
					    ? := -sqlcode; ? := sqlerrm;
					  end;
					""");
		}
		String sql = block.append("end;").toString().replace(":context", String.valueOf(context));
		try (CallableStatement statement = connection.prepareCall(sql)) {
			for (int i = 0; i < names.size(); i++) {
				int first = 1 + i * BINDS_PER_NAME;
				statement.setString(first, names.get(i));
				for (int out = first + 1; out <= first + 4; out++) {
					statement.registerOutParameter(out, Types.VARCHAR);
				}
				statement.registerOutParameter(first + 5, Types.NUMERIC);
				statement.registerOutParameter(first + 6, Types.NUMERIC);
				statement.registerOutParameter(first + 7, Types.NUMERIC);
				statement.registerOutParameter(first + 8, Types.VARCHAR);
			}
			statement.execute();
			List<Resolution> resolutions = new ArrayList<>();
			for (int i = 0; i < names.size(); i++) {
				int first = 1 + i * BINDS_PER_NAME;
				// the error is assigned only when NAME_RESOLVE raised one: null, read as 0, when it resolved
				int error = statement.getInt(first + 7);
				if (error != 0 && !NOT_RESOLVED.contains(error)) {
					throw new SqlFailure(sql, new SQLException(statement.getString(first + 8), null, error),
							PlsqlFailure.Phase.DICTIONARY);
				}
				resolutions.add(error != 0
						? new Resolution(error, statement.getString(first + 8), null, null, null, null, 0)
						: new Resolution(0, null, statement.getString(first + 1), statement.getString(first + 2),
								statement.getString(first + 3), statement.getString(first + 4),
								statement.getInt(first + 5)));
			}
			return resolutions;
		} catch (SQLException e) {
			throw new SqlFailure(sql, e, PlsqlFailure.Phase.DICTIONARY);
		} catch (io.github.rponte.penitent.internal.driver.DriverFailure e) {
			throw e.ofCall("reading the dictionary", sql, PlsqlFailure.Phase.DICTIONARY);
		}
	}

	/** In each name's block: the name; schema, part1, part2, dblink, its type, its object number; the error and its text. */
	private static final int BINDS_PER_NAME = 9;

	/** An object of the database: its owner and name. */
	public record ObjectName(String owner, String name) {
	}

	/**
	 * Where the object of one of {@code types}, {@code TYPE}, {@code PACKAGE}, or {@code TABLE} and {@code VIEW}, named
	 * {@code name} actually is, or empty: resolved by Oracle itself, with {@code NAME_RESOLVE} (#174), as PL/SQL would
	 * resolve the name a block declares. Unless {@code schemaNamed}, the name is resolved in the session's
	 * {@code current_schema}, which {@code owner} is: the object itself, then a private synonym, then a public one,
	 * through synonyms of synonyms; with the schema named, no public synonym counts. The object must be of a kind
	 * asked for: a name that is a function, for a package, does not resolve.
	 *
	 * <p>It replaced a loop of {@code ALL_OBJECTS} and {@code ALL_SYNONYMS}, a query or two per synonym, and
	 * {@code ALL_SYNONYMS} costs about 50 ms a query on 21c: a type through a synonym took about 50 ms there, through a
	 * synonym of a synonym about 100 ms, against under a millisecond.
	 */
	public java.util.Optional<ObjectName> objectBehind(String owner, String name, boolean schemaNamed,
			String... types) {
		List<String> kinds = List.of(types);
		int context = kinds.contains("TYPE") ? TYPE : kinds.contains("PACKAGE") ? PLSQL : TABLE;
		String quoted = schemaNamed ? '"' + owner + "\".\"" + name + '"' : '"' + name + '"';
		Resolution resolution = resolve(List.of(quoted), context).get(0);
		if (!resolution.resolved() || resolution.dblink() != null || !kinds.contains(kindOf(resolution.type()))) {
			return java.util.Optional.empty();
		}
		return java.util.Optional.of(new ObjectName(resolution.schema(),
				resolution.part2() != null ? resolution.part2() : resolution.part1()));
	}

	/** The kind of object {@code NAME_RESOLVE}'s {@code part1_type} names, as the dictionary's object types. */
	private static String kindOf(int type) {
		return switch (type) {
			case 2 -> "TABLE";
			case 4 -> "VIEW";
			case 9 -> "PACKAGE";
			case 13 -> "TYPE";
			default -> "";
		};
	}

	// ── Routines ─────────────────────────────────────────────────────────────────────────────────────────

	/**
	 * The signatures of {@code routineNames}, in {@code packageName} of {@code owner}, or outside a package when it is
	 * {@code null}. A name without rows, as a routine that does not exist, is left out.
	 *
	 * <p>The count of versions joins a grouped query on the owner and the package, as binds: a count correlated to
	 * each row of {@code ALL_ARGUMENTS}, as {@code p.owner = a.owner}, keeps Oracle from pushing the predicates into
	 * the view {@code ALL_PROCEDURES}, and measured about 15 ms per routine, against a fraction of a millisecond.
	 */
	public RoutineSignatures signatures(String owner, String packageName, Collection<String> routineNames) {
		List<String> names = List.copyOf(new LinkedHashSet<>(routineNames));
		Map<String, Map<String, List<Argument>>> versions = new LinkedHashMap<>();
		Map<String, Integer> counted = new HashMap<>();
		for (int from = 0; from < names.size(); from += RoutineSignatures.NAMES_PER_QUERY) {
			List<String> chunk = names.subList(from, Math.min(names.size(), from + RoutineSignatures.NAMES_PER_QUERY));
			String marks = chunk.stream().map(n -> "?").collect(Collectors.joining(", "));
			// the same binds twice: in the count of versions, then in the arguments
			List<Object> once = new ArrayList<>();
			once.add(owner);
			if (packageName != null) {
				once.add(packageName);
			}
			once.addAll(chunk);
			List<Object> binds = new ArrayList<>(once);
			binds.addAll(once);
			query(signaturesSql(packageName != null, marks), (rs, n) -> {
				String routine = rs.getString("object_name");
				counted.put(routine, rs.getInt("versions"));
				List<Argument> version = versions.computeIfAbsent(routine, k -> new LinkedHashMap<>())
						.computeIfAbsent(String.valueOf(rs.getString("overload")), k -> new ArrayList<>());
				// a version without arguments shows up as a row without a type
				if (rs.getString("data_type") != null) {
					version.add(new Argument(
							rs.getInt("position"),
							rs.getString("argument_name"),
							rs.getString("data_type"),
							rs.getString("in_out"),
							rs.getString("type_owner"),
							rs.getString("type_name"),
							rs.getString("type_subname"),
							"Y".equals(rs.getString("defaulted"))));
				}
				return null;
			}, binds.toArray());
		}
		Map<String, RoutineSignatures.Listing> routines = new HashMap<>();
		versions.forEach((routine, byOverload) -> {
			Map<String, List<Argument>> copied = new LinkedHashMap<>();
			byOverload.forEach((overload, arguments) -> copied.put(overload, List.copyOf(arguments)));
			routines.put(routine, new RoutineSignatures.Listing(java.util.Collections.unmodifiableMap(copied), counted.get(routine)));
		});
		return RoutineSignatures.of(routines);
	}

	private static String signaturesSql(boolean inPackage, String marks) {
		if (inPackage) {
			return """
					with versions as (
					  select procedure_name, count(*) as versions
					    from all_procedures
					   where owner = ? and object_name = ? and procedure_name in (:names)
					   group by procedure_name)
					select a.object_name, a.overload, a.position, a.argument_name, a.data_type, a.in_out,
					       a.type_owner, a.type_name, a.type_subname, a.defaulted, v.versions
					  from all_arguments a
					  join versions v on v.procedure_name = a.object_name
					 where a.owner = ? and a.package_name = ? and a.object_name in (:names) and a.data_level = 0
					 order by a.object_name, a.overload nulls first, a.position
					""".replace(":names", marks);
		}
		return """
				with versions as (
				  select object_name, count(*) as versions
				    from all_procedures
				   where owner = ? and object_name in (:names) and procedure_name is null
				     and object_type in ('FUNCTION', 'PROCEDURE')
				   group by object_name)
				select a.object_name, a.overload, a.position, a.argument_name, a.data_type, a.in_out,
				       a.type_owner, a.type_name, a.type_subname, a.defaulted, v.versions
				  from all_arguments a
				  join versions v on v.object_name = a.object_name
				 where a.owner = ? and a.package_name is null and a.object_name in (:names) and a.data_level = 0
				 order by a.object_name, a.overload nulls first, a.position
				""".replace(":names", marks);
	}

	/**
	 * How many versions {@code ALL_PROCEDURES} lists of {@code routine}, in {@code packageName}, or outside a package
	 * when it is {@code null}: a version without arguments has no rows in {@code ALL_ARGUMENTS}.
	 */
	public int versionsOf(String owner, String packageName, String routine) {
		if (packageName == null) {
			return count("""
					select count(*) from all_procedures
					 where owner = ? and object_name = ? and procedure_name is null
					   and object_type in ('FUNCTION', 'PROCEDURE')
					""", owner, routine);
		}
		return count("""
				select count(*) from all_procedures
				 where owner = ? and object_name = ? and procedure_name = ?
				""", owner, packageName, routine);
	}

	/** An object's type, as {@code PACKAGE} or {@code FUNCTION}, and its status, {@code VALID} or {@code INVALID}. */
	public record ObjectStatus(String type, String status) {

		public boolean isInvalid() {
			return "INVALID".equals(status);
		}
	}

	/**
	 * The status of {@code name}, of one of {@code types}, in {@code owner}, or empty if there is none (#184, A2): a
	 * routine that failed to compile has no rows in {@code ALL_ARGUMENTS} nor in {@code ALL_PROCEDURES}, and only
	 * {@code ALL_OBJECTS} still lists it.
	 */
	public java.util.Optional<ObjectStatus> statusOf(String owner, String name, String... types) {
		List<Object> binds = new ArrayList<>(List.of(owner, name));
		binds.addAll(List.of(types));
		List<ObjectStatus> found = query(("select object_type, status from all_objects"
				+ " where owner = ? and object_name = ? and object_type in (:types)")
				.replace(":types", marks(types.length)),
				(rs, n) -> new ObjectStatus(rs.getString("object_type"), rs.getString("status")), binds.toArray());
		return found.stream().findFirst();
	}

	/** The routines of a package, by name: for the error that says which exist. */
	public List<String> routinesOf(String owner, String packageName) {
		return strings("""
				select distinct procedure_name from all_procedures
				 where owner = ? and object_name = ? and procedure_name is not null
				 order by procedure_name
				""", owner, packageName);
	}

	// ── Types ────────────────────────────────────────────────────────────────────────────────────────────

	/**
	 * One attribute of a composite, or a column of a {@code %ROWTYPE}: its position, name and type, and the type's
	 * modifier, {@code REF} for a pointer to a row of an object table, or null.
	 */
	public record Attribute(int position, String name, String typeName, String typeOwner, String typePackage,
			String modifier) {

		/** Whether it is an object-relational {@code REF}, a pointer to a row of an object table, not the object. */
		public boolean isRef() {
			return "REF".equals(modifier);
		}
	}

	/** What a collection holds: its element's owner, package and name, as the dictionary lists them, and modifier. */
	public record Element(String owner, String packageName, String name, String modifier) {

		/** Whether the collection holds object-relational {@code REF}s, pointers to rows, not the objects. */
		public boolean isRef() {
			return "REF".equals(modifier);
		}
	}

	/**
	 * What the dictionary says of a collection, in one query: its element, whose name is the element's type name as
	 * the dictionary spells it, even a scalar's, as {@code TIMESTAMP(6) WITH TIME ZONE}; and its {@code INDEX BY}, as
	 * {@code PLS_INTEGER}, or {@code null}, as a collection of the schema always is.
	 */
	public record CollectionOf(Element element, String indexBy) {
	}

	/**
	 * The typecode of each of {@code types}, as {@code OBJECT} or {@code COLLECTION}, of {@code packageName} or, when
	 * it is {@code null}, of the schema, in a query per {@value RoutineSignatures#NAMES_PER_QUERY} names (#169). A type
	 * that is not there is left out.
	 */
	public Map<String, String> typecodesOf(String owner, String packageName, Collection<String> types) {
		Map<String, String> typecodes = new HashMap<>();
		for (List<String> chunk : chunks(types)) {
			List<Object> binds = new ArrayList<>(List.of(owner));
			if (packageName != null) {
				binds.add(packageName);
			}
			binds.addAll(chunk);
			query((packageName != null
					? "select type_name, typecode from all_plsql_types"
							+ " where owner = ? and package_name = ? and type_name in (:names)"
					: "select type_name, typecode from all_types where owner = ? and type_name in (:names)")
					.replace(":names", marks(chunk.size())), (rs, n) -> {
						if (rs.getString("typecode") != null) {
							typecodes.put(rs.getString("type_name"), rs.getString("typecode"));
						}
						return null;
					}, binds.toArray());
		}
		return typecodes;
	}

	/**
	 * The attributes of each of {@code types}, of one kind, in order, each from the view the dictionary keeps them in:
	 * an {@code OBJECT}'s in {@code ALL_TYPE_ATTRS}, a {@code RECORD}'s in {@code ALL_PLSQL_TYPE_ATTRS}, a
	 * {@code %ROWTYPE}'s as its table's columns, in a query per {@value RoutineSignatures#NAMES_PER_QUERY} names. An
	 * invisible column has no {@code COLUMN_ID}, and PL/SQL leaves it out of a {@code %ROWTYPE}. An {@code OBJECT}
	 * only references schema-level types, so its attributes have no package. A type that is not there is left out.
	 */
	public Map<String, List<Attribute>> attributesOf(CompositeType.Kind kind, String owner, String packageName,
			Collection<String> types) {
		Map<String, List<Attribute>> attributes = new HashMap<>();
		for (List<String> chunk : chunks(types)) {
			List<Object> binds = new ArrayList<>(List.of(owner));
			if (kind == CompositeType.Kind.PLSQL_RECORD) {
				binds.add(packageName);
			}
			binds.addAll(chunk);
			String sql = switch (kind) {
				case OBJECT -> """
						select type_name, attr_no, attr_name, attr_type_name, attr_type_owner,
						       null as attr_type_package, attr_type_mod
						  from all_type_attrs
						 where owner = ? and type_name in (:names)
						 order by type_name, attr_no
						""";
				case PLSQL_RECORD -> """
						select type_name, attr_no, attr_name, attr_type_name, attr_type_owner, attr_type_package,
						       attr_type_mod
						  from all_plsql_type_attrs
						 where owner = ? and package_name = ? and type_name in (:names)
						 order by type_name, attr_no
						""";
				case ROWTYPE -> """
						select table_name as type_name, column_id as attr_no, column_name as attr_name,
						       data_type as attr_type_name, data_type_owner as attr_type_owner,
						       null as attr_type_package, data_type_mod as attr_type_mod
						  from all_tab_columns
						 where owner = ? and table_name in (:names) and column_id is not null
						 order by table_name, column_id
						""";
			};
			query(sql.replace(":names", marks(chunk.size())), (rs, n) -> attributes
					.computeIfAbsent(rs.getString("type_name"), k -> new ArrayList<>())
					.add(new Attribute(rs.getInt("attr_no"), rs.getString("attr_name"), rs.getString("attr_type_name"),
							rs.getString("attr_type_owner"), rs.getString("attr_type_package"),
							rs.getString("attr_type_mod"))), binds.toArray());
		}
		return attributes;
	}

	/**
	 * What each of {@code collections} holds, of {@code packageName} or, when it is {@code null}, of the schema, in a
	 * query per {@value RoutineSignatures#NAMES_PER_QUERY} names. A collection that is not there is left out.
	 */
	public Map<String, CollectionOf> collectionsOf(String owner, String packageName, Collection<String> collections) {
		Map<String, CollectionOf> found = new HashMap<>();
		for (List<String> chunk : chunks(collections)) {
			List<Object> binds = new ArrayList<>(List.of(owner));
			if (packageName != null) {
				binds.add(packageName);
			}
			binds.addAll(chunk);
			query((packageName != null
					? """
							select type_name, elem_type_owner, elem_type_package, elem_type_name, elem_type_mod, index_by
							  from all_plsql_coll_types
							 where owner = ? and package_name = ? and type_name in (:names)
							"""
					: """
							select type_name, elem_type_owner, null as elem_type_package, elem_type_name, elem_type_mod,
							       null as index_by
							  from all_coll_types
							 where owner = ? and type_name in (:names)
							""").replace(":names", marks(chunk.size())), (rs, n) -> found.put(rs.getString("type_name"),
					new CollectionOf(new Element(rs.getString("elem_type_owner"), rs.getString("elem_type_package"),
							rs.getString("elem_type_name"), rs.getString("elem_type_mod")), rs.getString("index_by"))),
					binds.toArray());
		}
		return found;
	}

	private static List<List<String>> chunks(Collection<String> names) {
		List<String> distinct = List.copyOf(new LinkedHashSet<>(names));
		List<List<String>> chunks = new ArrayList<>();
		for (int from = 0; from < distinct.size(); from += RoutineSignatures.NAMES_PER_QUERY) {
			chunks.add(distinct.subList(from, Math.min(distinct.size(), from + RoutineSignatures.NAMES_PER_QUERY)));
		}
		return chunks;
	}

	private static String marks(int count) {
		return String.join(", ", java.util.Collections.nCopies(count, "?"));
	}
}
