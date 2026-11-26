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

	/** The first row's value, or empty when there is no row or its value is null, as an {@code INDEX_BY} may be. */
	private static java.util.Optional<String> first(List<String> values) {
		return values.isEmpty() ? java.util.Optional.empty() : java.util.Optional.ofNullable(values.get(0));
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
		StringBuilder block = new StringBuilder("begin\n");
		for (int i = 0; i < names.size(); i++) {
			block.append("""
					  begin
					    dbms_utility.name_resolve(?, 1, ?, ?, ?, ?, ?, ?);
					  exception when others then
					    ? := -sqlcode; ? := sqlerrm;
					  end;
					""");
		}
		String sql = block.append("end;").toString();
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

	private static final int MAX_SYNONYMS = 10;

	/**
	 * Where the object of one of {@code types}, as {@code TYPE}, {@code PACKAGE} or {@code TABLE}, named {@code name}
	 * in {@code owner} actually is, or empty: the object itself, then a private synonym of {@code owner} and, unless
	 * {@code schemaNamed}, a public one. A synonym can point to another, hence the loop; the limit only keeps it from
	 * running forever on a cycle. A block's {@code @PlsqlType} names resolve so; {@code NAME_RESOLVE}, which resolves
	 * the routines' (#174), takes a type with another context, not measured yet.
	 */
	public java.util.Optional<ObjectName> objectBehind(String owner, String name, boolean schemaNamed,
			String... types) {
		String in = String.join(", ", java.util.Collections.nCopies(types.length, "?"));
		ObjectName current = new ObjectName(owner, name);
		for (int i = 0; i <= MAX_SYNONYMS; i++) {
			List<Object> parameters = new ArrayList<>(List.of(current.owner(), current.name()));
			parameters.addAll(List.of(types));
			if (count("select count(*) from all_objects where owner = ? and object_name = ?"
					+ " and object_type in (" + in + ")", parameters.toArray()) > 0) {
				return java.util.Optional.of(current);
			}
			List<ObjectName> next = query("""
					select table_owner, table_name
					  from all_synonyms
					 where synonym_name = ? and db_link is null
					   and (owner = ? or (owner = 'PUBLIC' and ? = 'S'))
					 order by case when owner = 'PUBLIC' then 1 else 0 end
					""",
					(rs, n) -> new ObjectName(rs.getString("table_owner"), rs.getString("table_name")),
					current.name(), current.owner(), schemaNamed ? "N" : "S");
			if (next.isEmpty()) {
				return java.util.Optional.empty();
			}
			current = next.get(0);
			schemaNamed = true;
		}
		return java.util.Optional.empty();
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

	/** The routines of a package, by name: for the error that says which exist. */
	public List<String> routinesOf(String owner, String packageName) {
		return strings("""
				select distinct procedure_name from all_procedures
				 where owner = ? and object_name = ? and procedure_name is not null
				 order by procedure_name
				""", owner, packageName);
	}

	// ── Types ────────────────────────────────────────────────────────────────────────────────────────────

	/** A type's {@code typecode}, as {@code OBJECT} or {@code COLLECTION}, of a package or of the schema; or empty. */
	public java.util.Optional<String> typecodeOf(String owner, String packageName, String type) {
		List<String> found = packageName != null
				? strings("""
						select typecode from all_plsql_types
						 where owner = ? and package_name = ? and type_name = ?
						""", owner, packageName, type)
				: strings("select typecode from all_types where owner = ? and type_name = ?", owner, type);
		return first(found);
	}

	/** One attribute of a composite, or a column of a {@code %ROWTYPE}: its position, name and type. */
	public record Attribute(int position, String name, String typeName, String typeOwner, String typePackage) {
	}

	/**
	 * The attributes of a composite, in order, each from the view the dictionary keeps them in: an {@code OBJECT}'s
	 * in {@code ALL_TYPE_ATTRS}, a {@code RECORD}'s in {@code ALL_PLSQL_TYPE_ATTRS}, a {@code %ROWTYPE}'s as its
	 * table's columns. An invisible column has no {@code COLUMN_ID}, and PL/SQL leaves it out of a {@code %ROWTYPE}.
	 * An {@code OBJECT} only references schema-level types, so its attributes have no package.
	 */
	public List<Attribute> attributesOf(CompositeType.Kind kind, String owner, String packageName, String type) {
		return switch (kind) {
			case OBJECT -> query("""
					select attr_no, attr_name, attr_type_name, attr_type_owner
					  from all_type_attrs
					 where owner = ? and type_name = ?
					 order by attr_no
					""", (rs, n) -> new Attribute(rs.getInt("attr_no"), rs.getString("attr_name"),
					rs.getString("attr_type_name"), rs.getString("attr_type_owner"), null), owner, type);
			case PLSQL_RECORD -> query("""
					select attr_no, attr_name, attr_type_name, attr_type_owner, attr_type_package
					  from all_plsql_type_attrs
					 where owner = ? and package_name = ? and type_name = ?
					 order by attr_no
					""", (rs, n) -> new Attribute(rs.getInt("attr_no"), rs.getString("attr_name"),
					rs.getString("attr_type_name"), rs.getString("attr_type_owner"),
					rs.getString("attr_type_package")), owner, packageName, type);
			case ROWTYPE -> query("""
					select column_id, column_name, data_type, data_type_owner
					  from all_tab_columns
					 where owner = ? and table_name = ? and column_id is not null
					 order by column_id
					""", (rs, n) -> new Attribute(rs.getInt("column_id"), rs.getString("column_name"),
					rs.getString("data_type"), rs.getString("data_type_owner"), null), owner, type);
		};
	}

	/** The names of the JSON fields of a {@code RECORD}, or of the JSON columns behind a {@code %ROWTYPE}. */
	public List<String> jsonAttributesOf(CompositeType.Kind kind, String owner, String packageName, String type) {
		return switch (kind) {
			case PLSQL_RECORD -> strings("""
					select attr_name from all_plsql_type_attrs
					 where owner = ? and package_name = ? and type_name = ? and attr_type_name = 'JSON'
					 order by attr_no
					""", owner, packageName, type);
			case ROWTYPE -> strings("""
					select column_name from all_tab_columns
					 where owner = ? and table_name = ? and column_id is not null and data_type = 'JSON'
					 order by column_id
					""", owner, type);
			case OBJECT -> List.of();
		};
	}

	/** What a collection holds: its element's owner, package and name, as the dictionary lists them. */
	public record Element(String owner, String packageName, String name) {
	}

	/** The element of {@code collection}, of a package or of the schema; or empty. */
	public java.util.Optional<Element> elementOf(String owner, String packageName, String collection) {
		RowMapper<Element> toElement = (rs, n) -> new Element(rs.getString("elem_type_owner"),
				rs.getString("elem_type_package"), rs.getString("elem_type_name"));
		List<Element> elements = packageName != null
				? query("""
						select elem_type_owner, elem_type_package, elem_type_name
						  from all_plsql_coll_types
						 where owner = ? and package_name = ? and type_name = ?
						""", toElement, owner, packageName, collection)
				: query("""
						select elem_type_owner, null as elem_type_package, elem_type_name
						  from all_coll_types
						 where owner = ? and type_name = ?
						""", toElement, owner, collection);
		return elements.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(elements.get(0));
	}

	/** The element's type name as the dictionary spells it, even a scalar's: {@code TIMESTAMP(6) WITH TIME ZONE}. */
	public java.util.Optional<String> elementTypeNameOf(String owner, String packageName, String collection) {
		List<String> names = packageName != null
				? strings("""
						select elem_type_name
						  from all_plsql_coll_types
						 where owner = ? and package_name = ? and type_name = ?
						""", owner, packageName, collection)
				: strings("""
						select elem_type_name
						  from all_coll_types
						 where owner = ? and type_name = ?
						""", owner, collection);
		return first(names);
	}

	/**
	 * A package collection's index, as {@code PLS_INTEGER}; or empty when it is not {@code INDEX BY}, as no
	 * collection of the schema is, which is not asked.
	 */
	public java.util.Optional<String> indexByOf(String owner, String packageName, String collection) {
		if (packageName == null) {
			return java.util.Optional.empty();
		}
		return first(strings("""
				select index_by
				  from all_plsql_coll_types
				 where owner = ? and package_name = ? and type_name = ?
				""", owner, packageName, collection));
	}
}
