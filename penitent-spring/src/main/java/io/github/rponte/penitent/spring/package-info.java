/**
 * Spring integration: the call takes part in the current transaction, and a database failure arrives
 * as a {@code DataAccessException}, except a routine error, which stays a
 * {@code PlsqlRoutineRaisedException}.
 *
 * <ul>
 * <li>{@link io.github.rponte.penitent.spring.SpringPlsqlPackages} creates an interface by hand.
 * <li>{@link io.github.rponte.penitent.spring.EnablePlsqlPackages} registers every interface found in the
 * given packages as a bean, and may validate them at startup.
 * <li>{@link io.github.rponte.penitent.spring.PlsqlPackagesScanner} and
 * {@link io.github.rponte.penitent.spring.PlsqlPackagesValidator} are what the annotation and the Spring
 * Boot starter use to register and validate the interfaces.
 * </ul>
 */
package io.github.rponte.penitent.spring;
