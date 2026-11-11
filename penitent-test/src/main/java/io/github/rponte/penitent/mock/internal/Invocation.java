package io.github.rponte.penitent.mock.internal;

import java.lang.reflect.Method;
import java.util.List;

/** A call made on a mock: which method, on which mock, and a matcher for each of its arguments. */
public record Invocation(MockHandler mock, Method method, List<Matcher> matchers) {
}
