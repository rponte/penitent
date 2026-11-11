package io.github.rponte.penitent.spring.internal;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.rponte.penitent.internal.transaction.TransactionIntegration;

/**
 * Spring, as the core sees it. Found through {@code META-INF/services}, so it applies wherever
 * {@code penitent-spring} is on the classpath, with or without a Spring context.
 */
public final class SpringTransactionIntegration implements TransactionIntegration {

	/**
	 * Inside a Spring transaction, a block would take another connection and commit apart from it, even
	 * when Spring then rolls back.
	 */
	@Override
	public void refuseBlock() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("a Spring transaction is active on this thread, and a PlsqlTransaction"
					+ " would commit apart from it: in a Spring application, Spring owns the transactions, so use"
					+ " @Transactional or TransactionTemplate, whose interfaces already take part, or open the block"
					+ " outside any Spring transaction");
		}
	}

	/**
	 * The {@code DataSource} an interface of {@code penitent-spring} wraps, so that calling it without
	 * {@code tx.using(...)} inside a block of that {@code DataSource} is refused, as in the core.
	 */
	@Override
	public DataSource target(DataSource dataSource) {
		DataSource target = dataSource;
		while (target instanceof TransactionAwareDataSourceProxy proxy && proxy.getTargetDataSource() != null) {
			target = proxy.getTargetDataSource();
		}
		return target;
	}
}
