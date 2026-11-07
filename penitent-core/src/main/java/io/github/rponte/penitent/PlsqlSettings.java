package io.github.rponte.penitent;

import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.DefaultPlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlExceptionTranslator;

/**
 * What applies to every interface created with it, in one immutable object: the converters, the
 * default {@link PlsqlBind} and the exception translator.
 *
 * <p>"Global" means one object passed to every {@code create}, not static state: two contexts, or two
 * tests, can use different settings, and a proxy never changes behavior after it is created.
 *
 * <pre>{@code
 * PlsqlSettings settings = PlsqlSettings.defaults()
 *         .withBind(PlsqlBind.BY_NAME)
 *         .withConverters(PlsqlConverters.of(new MoneyConverter()));
 *
 * Orders orders = PlsqlPackages.create(Orders.class, dataSource, settings);
 * }</pre>
 *
 * <p>What an interface or a parameter declares wins over the settings: {@link PlsqlPackage#bind()} or
 * {@link PlsqlSchema#bind()} over {@link #bind()}, and {@code @PlsqlConvert} over {@link #converters()}.
 */
public final class PlsqlSettings {

	private static final PlsqlSettings DEFAULTS = new PlsqlSettings(PlsqlConverters.of(), PlsqlBind.BY_POSITION,
			null);

	private final PlsqlConverters converters;
	private final PlsqlBind bind;
	private final PlsqlExceptionTranslator exceptionTranslator;

	private PlsqlSettings(PlsqlConverters converters, PlsqlBind bind, PlsqlExceptionTranslator exceptionTranslator) {
		this.converters = converters;
		this.bind = bind;
		this.exceptionTranslator = exceptionTranslator;
	}

	/** No converters, {@link PlsqlBind#BY_POSITION}, and the creator's exception translator. */
	public static PlsqlSettings defaults() {
		return DEFAULTS;
	}

	/** Replaces the converters. To add to the current ones, use {@code converters().with(...)}. */
	public PlsqlSettings withConverters(PlsqlConverters converters) {
		return new PlsqlSettings(Objects.requireNonNull(converters, "converters"), bind, exceptionTranslator);
	}

	/** The bind of the interfaces that do not declare one. {@link PlsqlBind#DEFAULT} is not a choice. */
	public PlsqlSettings withBind(PlsqlBind bind) {
		Objects.requireNonNull(bind, "bind");
		if (bind == PlsqlBind.DEFAULT) {
			throw new IllegalArgumentException("PlsqlBind.DEFAULT means \"use the settings\", so the settings need"
					+ " BY_POSITION or BY_NAME");
		}
		return new PlsqlSettings(converters, bind, exceptionTranslator);
	}

	public PlsqlSettings withExceptionTranslator(PlsqlExceptionTranslator exceptionTranslator) {
		return new PlsqlSettings(converters, bind,
				Objects.requireNonNull(exceptionTranslator, "exceptionTranslator"));
	}

	public PlsqlConverters converters() {
		return converters;
	}

	public PlsqlBind bind() {
		return bind;
	}

	/**
	 * Empty when not set: the default then depends on who creates the proxy, which is
	 * {@link DefaultPlsqlExceptionTranslator} in {@link PlsqlPackages#create(Class, DataSource, PlsqlSettings)}
	 * and the Spring translator in the {@code penitent-spring} module.
	 */
	public Optional<PlsqlExceptionTranslator> exceptionTranslator() {
		return Optional.ofNullable(exceptionTranslator);
	}

	@Override
	public String toString() {
		return "PlsqlSettings[converters=" + converters + ", bind=" + bind + ", exceptionTranslator="
				+ (exceptionTranslator == null ? "creator's default" : exceptionTranslator.getClass().getName()) + "]";
	}
}
