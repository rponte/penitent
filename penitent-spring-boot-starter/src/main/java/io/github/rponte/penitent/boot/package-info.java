/**
 * Spring Boot autoconfiguration: every interface in the application's packages becomes a bean, with
 * no annotation.
 *
 * <ul>
 * <li>{@link io.github.rponte.penitent.boot.PlsqlAutoConfiguration} registers the interfaces and builds the
 * {@code PlsqlSettings}.
 * <li>{@link io.github.rponte.penitent.boot.PlsqlProperties} holds the {@code penitent.*} properties.
 * <li>{@link io.github.rponte.penitent.boot.PlsqlSettingsCustomizer} adds converters or an exception
 * translator in code, keeping the properties.
 * </ul>
 */
package io.github.rponte.penitent.boot;
