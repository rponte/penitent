package io.github.rponte.penitent.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.annotation.Order;

import io.github.rponte.penitent.PlsqlBind;
import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.boot.app.Application;
import io.github.rponte.penitent.boot.app.Arithmetic;
import io.github.rponte.penitent.boot.app.Orders;
import io.github.rponte.penitent.boot.other.Invoices;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.spring.EnablePlsqlPackages;
import io.github.rponte.penitent.spring.PlsqlPackageFactoryBean;
import io.github.rponte.penitent.spring.PlsqlPackagesValidator;

/**
 * The autoconfiguration alone, without a database: creating a proxy opens no connection, so a
 * {@code DataSource} that refuses every connection is enough, and shows that validation did run.
 */
@ExtendWith(OutputCaptureExtension.class)
class PlsqlAutoConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class))
			.withUserConfiguration(Application.class)
			.withBean(DataSource.class, PlsqlAutoConfigurationTest::unavailable);

	@Test
	@DisplayName("registers the interfaces of the application's package, and nothing outside it")
	void registers_application_interfaces() {
		runner.run(context -> {
			assertThat(context).hasSingleBean(Orders.class).hasSingleBean(Arithmetic.class);
			assertThat(context).doesNotHaveBean(Orders.Nested.class).doesNotHaveBean(Invoices.class);
			assertThat(context.getBeanNamesForType(Orders.class)).containsExactly(Orders.class.getName());
		});
	}

	@Test
	@DisplayName("without a DataSource, it does nothing")
	void without_data_source() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class))
				.withUserConfiguration(Application.class)
				.run(context -> assertThat(context).hasNotFailed()
						.doesNotHaveBean(Orders.class).doesNotHaveBean(PlsqlSettings.class));
	}

	@Configuration
	static class TwoDataSources {

		@Bean
		DataSource first() {
			return unavailable();
		}

		@Bean
		DataSource second() {
			return unavailable();
		}
	}

	@Configuration
	static class PrimaryDataSource {

		@Bean
		@Primary
		DataSource main() {
			return unavailable();
		}

		@Bean
		DataSource reports() {
			return unavailable();
		}
	}

	@Test
	@DisplayName("with two DataSources it backs off, and with a primary one it uses it")
	void several_data_sources() {
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class))
				.withUserConfiguration(Application.class, TwoDataSources.class)
				.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(Orders.class));
		new ApplicationContextRunner()
				.withConfiguration(AutoConfigurations.of(PlsqlAutoConfiguration.class))
				.withUserConfiguration(Application.class, PrimaryDataSource.class)
				.run(context -> assertThat(context).hasSingleBean(Orders.class));
	}

	@Configuration
	@EnablePlsqlPackages(basePackageClasses = Invoices.class)
	static class ExplicitPackages {
	}

	@Test
	@DisplayName("an @EnablePlsqlPackages in the application wins: only its packages are scanned")
	void explicit_packages_win() {
		runner.withUserConfiguration(ExplicitPackages.class).run(context -> {
			assertThat(context).hasSingleBean(Invoices.class);
			assertThat(context).doesNotHaveBean(Orders.class);
		});
	}

	@Test
	@DisplayName("penitent.bind sets the default bind, which is BY_POSITION when unset")
	void bind_property() {
		runner.run(context -> assertThat(context.getBean(PlsqlSettings.class).bind()).isEqualTo(PlsqlBind.BY_POSITION));
		runner.withPropertyValues("penitent.bind=BY_NAME")
				.run(context -> assertThat(context.getBean(PlsqlSettings.class).bind()).isEqualTo(PlsqlBind.BY_NAME));
	}

	@Test
	@DisplayName("penitent.bind=DEFAULT is refused at startup, naming the property")
	void bind_default_refused() {
		runner.withPropertyValues("penitent.bind=DEFAULT").run(context -> assertThat(context).hasFailed()
				.getFailure().rootCause().hasMessageContaining("penitent.bind must be BY_POSITION or BY_NAME"));
	}

	record Tag(String value) {
	}

	@Configuration
	static class Customizers {

		static final PlsqlConverters TAGS = PlsqlConverters.of(PlsqlConverter.ofNullable(Tag.class, String.class,
				Tag::value, Tag::new));

		@Bean
		@Order(2)
		PlsqlSettingsCustomizer byName() {
			return settings -> settings.withBind(PlsqlBind.BY_NAME);
		}

		@Bean
		@Order(1)
		PlsqlSettingsCustomizer converters() {
			return settings -> settings.withConverters(TAGS).withBind(PlsqlBind.BY_POSITION);
		}
	}

	@Test
	@DisplayName("customizers apply on top of the properties, in @Order")
	void customizers() {
		runner.withUserConfiguration(Customizers.class).withPropertyValues("penitent.bind=BY_POSITION").run(context -> {
			PlsqlSettings settings = context.getBean(PlsqlSettings.class);
			assertThat(settings.converters()).isSameAs(Customizers.TAGS);
			assertThat(settings.bind()).as("the later customizer wins").isEqualTo(PlsqlBind.BY_NAME);
		});
	}

	@Configuration
	static class OwnSettings {

		static final PlsqlSettings SETTINGS = PlsqlSettings.defaults();

		@Bean
		PlsqlSettings mySettings() {
			return SETTINGS;
		}
	}

	@Test
	@DisplayName("a PlsqlSettings bean of the application wins, and penitent.bind set with it is a warning")
	void own_settings_win(CapturedOutput output) {
		runner.withUserConfiguration(OwnSettings.class, Customizers.class).withPropertyValues("penitent.bind=BY_NAME")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(PlsqlSettings.class)).isSameAs(OwnSettings.SETTINGS);
				});
		assertThat(output).contains("penitent.bind is set but not applied: a PlsqlSettings bean is defined");
	}

	@Test
	@DisplayName("without penitent.bind, a PlsqlSettings bean of the application is no warning")
	void own_settings_without_properties(CapturedOutput output) {
		runner.withUserConfiguration(OwnSettings.class).run(context -> assertThat(context).hasNotFailed());
		assertThat(output).doesNotContain("penitent.bind is set but not applied");
	}

	@Test
	@DisplayName("validation is off by default: the context starts without the database")
	void validation_off_by_default() {
		runner.run(context -> {
			assertThat(context).hasNotFailed().doesNotHaveBean(PlsqlPackagesValidator.class);
			assertThat(context.getBeansOfType(PlsqlPackageFactoryBean.class)).hasSize(2);
		});
	}

	@Test
	@DisplayName("penitent.validation.enabled checks every interface at startup, so without the database it stops")
	void validation_enabled() {
		runner.withPropertyValues("penitent.validation.enabled=true").run(context -> assertThat(context).hasFailed()
				.getFailure().rootCause().hasMessage("database is down"));
	}

	@Test
	@DisplayName("penitent.validation.enabled also covers interfaces registered by @EnablePlsqlPackages")
	void validation_covers_explicit_packages() {
		runner.withUserConfiguration(ExplicitPackages.class).withPropertyValues("penitent.validation.enabled=true")
				.run(context -> assertThat(context).hasFailed());
	}

	private static DataSource unavailable() {
		return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
				new Class<?>[] { DataSource.class }, (proxy, method, args) -> {
					if (method.getName().equals("getConnection")) {
						throw new SQLException("database is down");
					}
					if (method.getName().equals("toString")) {
						return "unavailable DataSource";
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}
}
