package io.github.rponte.penitent.spring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

import io.github.rponte.penitent.PlsqlPackages;
import io.github.rponte.penitent.exception.PlsqlMappingException;

/**
 * Validates registered interfaces with {@link PlsqlPackages#validate}, once every singleton exists. A
 * mismatch stops the context, with every interface that does not match in the message.
 *
 * <p>{@link EnablePlsqlPackages#validateOnStartup()} registers one for the interfaces it found; the
 * Spring Boot starter registers {@link #all()}, for every interface registered in the context.
 */
public final class PlsqlPackagesValidator implements SmartInitializingSingleton, BeanFactoryAware {

	private static final Log LOG = LogFactory.getLog(PlsqlPackagesValidator.class);

	/** {@code null} means every {@link PlsqlPackageFactoryBean} in the context. */
	private final List<String> beanNames;
	private BeanFactory beanFactory;

	PlsqlPackagesValidator(List<String> beanNames) {
		this.beanNames = beanNames == null ? null : List.copyOf(beanNames);
	}

	/** Validates every interface registered in the context, whoever registered it. */
	public static PlsqlPackagesValidator all() {
		return new PlsqlPackagesValidator(null);
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	@Override
	public void afterSingletonsInstantiated() {
		long start = System.nanoTime();
		List<String> names = beanNames != null ? beanNames : registeredInterfaces();
		List<PlsqlMappingException> errors = new ArrayList<>();
		for (String name : names) {
			try {
				PlsqlPackages.validate(beanFactory.getBean(name));
			} catch (PlsqlMappingException e) {
				errors.add(e);
			}
		}
		if (errors.size() == 1) {
			throw errors.get(0);
		}
		if (!errors.isEmpty()) {
			PlsqlMappingException all = new PlsqlMappingException(errors.size() + " interfaces do not match the"
					+ " database:\n" + errors.stream().map(PlsqlMappingException::getMessage)
							.collect(Collectors.joining("\n")));
			errors.forEach(all::addSuppressed);
			throw all;
		}
		LOG.info("Validated " + names.size() + " PL/SQL interface(s) against the database in "
				+ (System.nanoTime() - start) / 1_000_000 + " ms");
	}

	/** The names come with the {@code &} of a factory; the bean is the interface it creates. */
	private List<String> registeredInterfaces() {
		String[] factories = BeanFactoryUtils.beanNamesForTypeIncludingAncestors((ListableBeanFactory) beanFactory,
				PlsqlPackageFactoryBean.class, false, false);
		return Arrays.stream(factories).map(BeanFactoryUtils::transformedBeanName).sorted().toList();
	}
}
