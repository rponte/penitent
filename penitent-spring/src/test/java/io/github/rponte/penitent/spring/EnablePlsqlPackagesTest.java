package io.github.rponte.penitent.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.convert.PlsqlConverter;
import io.github.rponte.penitent.convert.PlsqlConverters;
import io.github.rponte.penitent.exception.PlsqlMappingException;
import io.github.rponte.penitent.spring.scan.Arithmetic;
import io.github.rponte.penitent.spring.scan.Holder.NestedItems;
import io.github.rponte.penitent.spring.scan.Items;
import io.github.rponte.penitent.spring.scan.NotMapped;
import io.github.rponte.penitent.spring.scan.nested.Names;
import io.github.rponte.penitent.spring.scansettings.Tags;
import io.github.rponte.penitent.spring.scanvalidate.FunctionAsVoid;
import io.github.rponte.penitent.spring.scanvalidate.MissingRoutine;
import io.github.rponte.penitent.spring.scansettings.Tags.Tag;

class EnablePlsqlPackagesTest {

	@Configuration
	@EnablePlsqlPackages("io.github.rponte.penitent.spring.scan")
	static class ScanConfig {

		@Bean
		DataSource dataSource() {
			return TestDatabase.dataSource();
		}

		@Bean
		TransactionTemplate transactionTemplate(DataSource dataSource) {
			return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		}

		@Bean
		ItemsClient itemsClient(Items items) {
			return new ItemsClient(items);
		}
	}

	/** Takes the interface in its constructor, as a service would. */
	record ItemsClient(Items items) {
	}

	@Test
	@DisplayName("an interface with @PlsqlPackage becomes a bean, injected by type, that calls the routine")
	void registers_and_injects() {
		try (var context = new AnnotationConfigApplicationContext(ScanConfig.class)) {
			Items items = context.getBean(ItemsClient.class).items();

			int before = items.countItems();
			items.insertItem(1000);
			assertThat(items.countItems()).isEqualTo(before + 1);
			TestDatabase.delete(1000);

			assertThat(context.getBean(Items.class)).isSameAs(items);
			assertThat(context.getBeanNamesForType(Items.class)).containsExactly(Items.class.getName());
		}
	}

	@Test
	@DisplayName("each interface has its own factory and its own proxy, the same on every lookup")
	void one_proxy_per_interface() {
		try (var context = new AnnotationConfigApplicationContext(ScanConfig.class)) {
			Items items = context.getBean(Items.class);
			Names names = context.getBean(Names.class);
			Arithmetic arithmetic = context.getBean(Arithmetic.class);

			assertThat(context.getBean(Items.class)).isSameAs(items);
			assertThat(context.getBean(Names.class)).isSameAs(names);
			assertThat(context.getBean(Arithmetic.class)).isSameAs(arithmetic);
			assertThat(context.getBeansOfType(PlsqlPackageFactoryBean.class)).hasSize(3);
			assertThat(context.getBean("&" + Items.class.getName()))
					.isNotSameAs(context.getBean("&" + Names.class.getName()));

			assertThat(names.echoNames(List.of("x"))).containsExactly("x");
			assertThat(arithmetic.sum(1, 1)).isEqualTo(2);
		}
	}

	@Test
	@DisplayName("an interface made only of @PlsqlBlock methods is found, though it has no annotation")
	void finds_blocks_only_interface() {
		try (var context = new AnnotationConfigApplicationContext(ScanConfig.class)) {
			assertThat(context.getBean(Arithmetic.class).sum(2, 3)).isEqualTo(5);
		}
	}

	@Test
	@DisplayName("subpackages are scanned, and an ordinary interface does not become a bean")
	void scans_subpackages_and_skips_other_interfaces() {
		try (var context = new AnnotationConfigApplicationContext(ScanConfig.class)) {
			assertThat(context.getBean(Names.class).echoNames(List.of("a"))).containsExactly("a");
			assertThat(context.getBeanNamesForType(NotMapped.class)).isEmpty();
		}
	}

	@Test
	@DisplayName("the bean takes part in the current transaction, and the rollback undoes what it did")
	void takes_part_in_transaction() {
		try (var context = new AnnotationConfigApplicationContext(ScanConfig.class)) {
			Items items = context.getBean(Items.class);
			TransactionTemplate transaction = context.getBean(TransactionTemplate.class);
			int before = items.countItems();

			transaction.executeWithoutResult(status -> {
				items.insertItem(1001);
				assertThat(items.countItems()).isEqualTo(before + 1);
				status.setRollbackOnly();
			});

			assertThat(items.countItems()).isEqualTo(before);
		}
	}

	@Configuration
	@EnablePlsqlPackages(basePackageClasses = Names.class)
	static class ByClassConfig {

		@Bean
		DataSource dataSource() {
			return TestDatabase.dataSource();
		}
	}

	@Test
	@DisplayName("basePackageClasses scans the package of each class, and only that")
	void base_package_classes() {
		try (var context = new AnnotationConfigApplicationContext(ByClassConfig.class)) {
			assertThat(context.getBean(Names.class).echoNames(List.of("b"))).containsExactly("b");
			assertThat(context.getBeanNamesForType(Items.class)).isEmpty();
		}
	}

	@Configuration
	@EnablePlsqlPackages(basePackageClasses = Names.class, dataSource = "second")
	static class NamedDataSourceConfig {

		static final AtomicInteger FIRST = new AtomicInteger();
		static final AtomicInteger SECOND = new AtomicInteger();

		@Bean
		DataSource first() {
			return counting(FIRST);
		}

		@Bean
		DataSource second() {
			return counting(SECOND);
		}
	}

	@Test
	@DisplayName("dataSource chooses the bean by name when there are several")
	void named_data_source() {
		try (var context = new AnnotationConfigApplicationContext(NamedDataSourceConfig.class)) {
			NamedDataSourceConfig.FIRST.set(0);
			NamedDataSourceConfig.SECOND.set(0);

			context.getBean(Names.class).echoNames(List.of("c"));

			assertThat(NamedDataSourceConfig.SECOND).hasPositiveValue();
			assertThat(NamedDataSourceConfig.FIRST).hasValue(0);
		}
	}

	@Configuration
	@EnablePlsqlPackages(basePackageClasses = Names.class)
	static class TwoDataSourcesConfig {

		@Bean
		DataSource first() {
			return TestDatabase.dataSource();
		}

		@Bean
		DataSource second() {
			return TestDatabase.newDataSource();
		}
	}

	@Test
	@DisplayName("with two DataSources and no name, the context does not start, and says which ones")
	void ambiguous_data_source() {
		assertThatThrownBy(() -> new AnnotationConfigApplicationContext(TwoDataSourcesConfig.class))
				.isInstanceOf(BeanCreationException.class)
				.hasRootCauseInstanceOf(NoUniqueBeanDefinitionException.class)
				.rootCause().hasMessageContaining("first").hasMessageContaining("second");
	}

	@Configuration
	@EnablePlsqlPackages("io.github.rponte.penitent.spring.scanbroken")
	static class BrokenConfig {

		@Bean
		DataSource dataSource() {
			return TestDatabase.dataSource();
		}
	}

	@Test
	@DisplayName("a mapping error stops the context at startup, even if nothing injects the interface")
	void mapping_error_at_startup() {
		assertThatThrownBy(() -> new AnnotationConfigApplicationContext(BrokenConfig.class))
				.isInstanceOf(BeanCreationException.class)
				.hasRootCauseInstanceOf(PlsqlMappingException.class)
				.rootCause().hasMessageContaining("WithoutContainer").hasMessageContaining("@PlsqlSchema");
	}

	@Configuration
	@EnablePlsqlPackages("io.github.rponte.penitent.spring.scan.nested")
	static class OverlappingConfig {
	}

	@Test
	@DisplayName("two scans that overlap register each interface once")
	void overlapping_scans() {
		try (var context = new AnnotationConfigApplicationContext(ScanConfig.class, OverlappingConfig.class)) {
			assertThat(context.getBeanNamesForType(Names.class)).containsExactly(Names.class.getName());
		}
	}

	@Configuration
	@EnablePlsqlPackages(value = "io.github.rponte.penitent.spring.scan", considerNested = true)
	static class NestedConfig {

		@Bean
		DataSource dataSource() {
			return TestDatabase.dataSource();
		}
	}

	@Test
	@DisplayName("a nested interface is skipped by default, and registered with considerNested")
	void nested_interfaces() {
		try (var context = new AnnotationConfigApplicationContext(ScanConfig.class)) {
			assertThat(context.getBeanNamesForType(NestedItems.class)).isEmpty();
		}
		try (var context = new AnnotationConfigApplicationContext(NestedConfig.class)) {
			assertThat(context.getBean(NestedItems.class).countItems()).isNotNegative();
		}
	}

	static final class Marked extends RuntimeException {
		Marked(Throwable cause) {
			super(cause);
		}
	}

	@Configuration
	@EnablePlsqlPackages(basePackageClasses = Tags.class)
	static class SettingsConfig {

		@Bean
		DataSource dataSource() {
			return TestDatabase.dataSource();
		}

		@Bean
		PlsqlSettings plsqlSettings() {
			return PlsqlSettings.defaults()
					.withConverters(PlsqlConverters.of(PlsqlConverter.ofNullable(Tag.class, String.class,
							Tag::value, Tag::new)))
					.withExceptionTranslator(failure -> new Marked(failure.cause()));
		}
	}

	@Test
	@DisplayName("a PlsqlSettings bean applies to every registered interface")
	void settings_bean() {
		try (var context = new AnnotationConfigApplicationContext(SettingsConfig.class)) {
			Tags tags = context.getBean(Tags.class);

			assertThat(tags.echo(List.of(new Tag("a")))).containsExactly(new Tag("a"));
			assertThatThrownBy(tags::raiseError).isInstanceOf(Marked.class);
		}
	}

	@Configuration
	@EnablePlsqlPackages(basePackageClasses = MissingRoutine.class)
	static class NotValidatedConfig {

		@Bean
		DataSource dataSource() {
			return TestDatabase.dataSource();
		}
	}

	@Configuration
	@EnablePlsqlPackages(basePackageClasses = MissingRoutine.class, validateOnStartup = true)
	static class ValidatedConfig {

		@Bean
		DataSource dataSource() {
			return TestDatabase.dataSource();
		}
	}

	@Configuration
	@EnablePlsqlPackages(value = "io.github.rponte.penitent.spring.scan", validateOnStartup = true)
	static class ValidScanConfig {

		@Bean
		DataSource dataSource() {
			return TestDatabase.dataSource();
		}
	}

	@Test
	@DisplayName("without validateOnStartup, a mismatch with the database only shows up on the call")
	void validation_is_off_by_default() {
		try (var context = new AnnotationConfigApplicationContext(NotValidatedConfig.class)) {
			MissingRoutine missing = context.getBean(MissingRoutine.class);

			assertThat(missing.countItems()).isNotNegative();
			assertThatThrownBy(missing::routineThatDoesNotExist).isInstanceOf(PlsqlMappingException.class);
		}
	}

	@Test
	@DisplayName("with validateOnStartup, the context does not start, and names every interface that does not match")
	void validation_on_startup() {
		assertThatThrownBy(() -> new AnnotationConfigApplicationContext(ValidatedConfig.class))
				.isInstanceOf(PlsqlMappingException.class)
				.hasMessageStartingWith("2 interfaces do not match the database")
				.hasMessageContaining(FunctionAsVoid.class.getName() + ".countItems")
				.hasMessageContaining(MissingRoutine.class.getName() + ".routineThatDoesNotExist");
	}

	@Test
	@DisplayName("with validateOnStartup, interfaces that match start normally")
	void validation_on_startup_passes() {
		try (var context = new AnnotationConfigApplicationContext(ValidScanConfig.class)) {
			assertThat(context.getBean(Items.class).countItems()).isNotNegative();
		}
	}

	private static DataSource counting(AtomicInteger connections) {
		return new DelegatingDataSource(TestDatabase.dataSource()) {
			@Override
			public Connection getConnection() throws SQLException {
				connections.incrementAndGet();
				return super.getConnection();
			}
		};
	}
}
