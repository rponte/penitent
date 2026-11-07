package io.github.rponte.penitent.boot;

import io.github.rponte.penitent.PlsqlSettings;

/**
 * Adjusts the {@link PlsqlSettings} the starter builds from the {@code penitent.*} properties, which keep
 * applying: the way to add converters or an exception translator in code without losing them. Every
 * customizer bean is applied, in {@code @Order}.
 *
 * <pre>{@code
 * @Bean
 * PlsqlSettingsCustomizer converters() {
 *     return settings -> settings.withConverters(PlsqlConverters.of(new TaxIdConverter()));
 * }
 * }</pre>
 *
 * <p>{@code PlsqlSettings} is immutable, so a customizer returns the settings to use, not changes the
 * ones it receives. As with Spring Boot's own customizers, it does not apply to a
 * {@code PlsqlSettings} bean declared by the application.
 */
@FunctionalInterface
public interface PlsqlSettingsCustomizer {

	PlsqlSettings customize(PlsqlSettings settings);
}
