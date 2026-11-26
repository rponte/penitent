package io.github.rponte.penitent.internal.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/**
 * The signatures of routines, as the dictionary listed them when read (#169): the rows of {@code ALL_ARGUMENTS}, by
 * routine and by version, with the count of versions from {@code ALL_PROCEDURES}, since a version without arguments
 * has no rows. A proxy of a package reads them for all of its interface's routines in one query, on the first call
 * of any method or by {@code validate}, and keeps them while it lives; each method is still described from them, and
 * checked, when it is first used. Immutable, so that threads may share it as it is.
 *
 * <p>Only the routines asked for are read, never the whole package: a package of hundreds of routines that an
 * interface maps a few of costs the rows of those few.
 */
public final class RoutineSignatures {

	/**
	 * An {@code IN} list takes at most 1000 expressions ({@code ORA-01795}); more names are read in as many queries.
	 */
	static final int NAMES_PER_QUERY = 1000;

	/** A routine's versions, by overload, in order, and how many {@code ALL_PROCEDURES} counts. */
	public record Listing(Map<String, List<Argument>> versions, int counted) {
	}

	private final String owner;
	private final String packageName;
	private final Map<String, Listing> routines;

	private RoutineSignatures(String owner, String packageName, Map<String, Listing> routines) {
		this.owner = owner;
		this.packageName = packageName;
		this.routines = routines;
	}

	/**
	 * The signatures of {@code routineNames}, in {@code packageName} of {@code owner}, or outside a package when it is
	 * {@code null}. A name without rows, as a routine that does not exist, is left out.
	 *
	 * <p>The count of versions joins a grouped query on the owner and the package, as binds: a count correlated to
	 * each row of {@code ALL_ARGUMENTS}, as {@code p.owner = a.owner}, keeps Oracle from pushing the predicates into
	 * the view {@code ALL_PROCEDURES}, and measured about 15 ms per routine, against a fraction of a millisecond.
	 */
	public static RoutineSignatures read(Dictionary dictionary, String owner, String packageName,
			Collection<String> routineNames) {
		List<String> names = List.copyOf(new LinkedHashSet<>(routineNames));
		Map<String, Map<String, List<Argument>>> versions = new LinkedHashMap<>();
		Map<String, Integer> counted = new HashMap<>();
		for (int from = 0; from < names.size(); from += NAMES_PER_QUERY) {
			List<String> chunk = names.subList(from, Math.min(names.size(), from + NAMES_PER_QUERY));
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
			dictionary.query(sql(packageName != null, marks), (rs, n) -> {
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
		Map<String, Listing> routines = new HashMap<>();
		versions.forEach((routine, byOverload) -> {
			Map<String, List<Argument>> copied = new LinkedHashMap<>();
			byOverload.forEach((overload, arguments) -> copied.put(overload, List.copyOf(arguments)));
			routines.put(routine, new Listing(java.util.Collections.unmodifiableMap(copied), counted.get(routine)));
		});
		return new RoutineSignatures(owner, packageName, Map.copyOf(routines));
	}

	private static String sql(boolean inPackage, String marks) {
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

	/** The routine's listing, or empty when {@code ALL_ARGUMENTS} has no rows for it, or it was not asked for. */
	public Optional<Listing> of(String routineName) {
		return Optional.ofNullable(routines.get(routineName));
	}

	/** Whether no routine asked for had rows. */
	public boolean isEmpty() {
		return routines.isEmpty();
	}

	/** Whether these are of the routines in {@code packageName} of {@code owner}, as resolved now. */
	public boolean isOf(String owner, String packageName) {
		return this.owner.equals(owner) && Objects.equals(this.packageName, packageName);
	}
}
