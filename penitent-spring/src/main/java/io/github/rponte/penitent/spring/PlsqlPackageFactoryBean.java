package io.github.rponte.penitent.spring;

import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.SmartFactoryBean;

import io.github.rponte.penitent.PlsqlSettings;

/**
 * Creates the bean of one interface registered by {@link EnablePlsqlPackages}, through
 * {@link SpringPlsqlPackages}: the same proxy a manual call would create, a singleton with a single
 * metadata cache. A {@link PlsqlSettings} bean, if there is one, applies to every interface.
 *
 * <p>The proxy is created when the context starts, even if nothing injects it, so that a mapping error,
 * such as an interface without a container, stops the startup instead of waiting for first use. The
 * routines themselves are still checked against the dictionary on their first call.
 *
 * <p>Registered by {@link PlsqlPackagesScanner}; an application does not use it directly.
 */
public final class PlsqlPackageFactoryBean<T> implements SmartFactoryBean<T>, BeanFactoryAware {

	private final Class<T> type;
	private String dataSourceName = "";
	private BeanFactory beanFactory;
	private volatile T proxy;

	public PlsqlPackageFactoryBean(Class<T> type) {
		this.type = Objects.requireNonNull(type, "type");
	}

	/** The {@link DataSource} bean to use; empty means the only one in the context. */
	public void setDataSourceName(String dataSourceName) {
		this.dataSourceName = dataSourceName == null ? "" : dataSourceName;
	}

	@Override
	public void setBeanFactory(BeanFactory beanFactory) {
		this.beanFactory = beanFactory;
	}

	/**
	 * Spring does not cache what a {@link SmartFactoryBean} returns, so the proxy is kept here: every
	 * injection gets the same one, with the same metadata cache.
	 */
	@Override
	public T getObject() {
		T current = proxy;
		if (current == null) {
			synchronized (this) {
				current = proxy;
				if (current == null) {
					current = SpringPlsqlPackages.create(type, dataSource(), settings());
					proxy = current;
				}
			}
		}
		return current;
	}

	/** The {@link PlsqlSettings} bean, if the context has one. */
	private PlsqlSettings settings() {
		return beanFactory.getBeanProvider(PlsqlSettings.class).getIfAvailable(PlsqlSettings::defaults);
	}

	private DataSource dataSource() {
		return dataSourceName.isEmpty()
				? beanFactory.getBean(DataSource.class)
				: beanFactory.getBean(dataSourceName, DataSource.class);
	}

	@Override
	public Class<T> getObjectType() {
		return type;
	}

	@Override
	public boolean isEagerInit() {
		return true;
	}
}
