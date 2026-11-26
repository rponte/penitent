package io.github.rponte.penitent.internal.metadata;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

	private final Map<String, Listing> routines;

	private RoutineSignatures(Map<String, Listing> routines) {
		this.routines = routines;
	}

	/** Built by {@link Dictionary#signatures}, which reads them. */
	static RoutineSignatures of(Map<String, Listing> routines) {
		return new RoutineSignatures(Map.copyOf(routines));
	}

	/** The routine's listing, or empty when {@code ALL_ARGUMENTS} has no rows for it, or it was not asked for. */
	public Optional<Listing> of(String routineName) {
		return Optional.ofNullable(routines.get(routineName));
	}

	/** Whether no routine asked for had rows. */
	public boolean isEmpty() {
		return routines.isEmpty();
	}

}
