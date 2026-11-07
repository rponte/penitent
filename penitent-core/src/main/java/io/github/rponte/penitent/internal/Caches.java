package io.github.rponte.penitent.internal;

import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * The lookup the caches use instead of {@link ConcurrentMap#computeIfAbsent}, whose function runs
 * with the bin locked. Here the function reads the dictionary, a round trip to the database, and
 * under that lock other keys in the same bin would wait for it; and a function that updated the
 * same map would fail with {@code Recursive update}.
 *
 * <p>The price is that two threads asking for the same key at the same time may both compute it;
 * the result is the same, and the first one stored wins.
 */
public final class Caches {

	private Caches() {
	}

	/** A {@code null} result is returned and not stored, as {@code computeIfAbsent} does. */
	public static <K, V> V get(ConcurrentMap<K, V> cache, K key, Function<? super K, ? extends V> compute) {
		V cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		V computed = compute.apply(key);
		if (computed == null) {
			return null;
		}
		V raced = cache.putIfAbsent(key, computed);
		return raced != null ? raced : computed;
	}
}
