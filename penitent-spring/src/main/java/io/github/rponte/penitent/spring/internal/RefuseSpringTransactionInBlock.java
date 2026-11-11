package io.github.rponte.penitent.spring.internal;

import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;

import io.github.rponte.penitent.internal.proxy.TransactionScope;

/**
 * Refuses a Spring transaction that would begin inside a {@code PlsqlTransaction}: it would take
 * another connection and commit apart from the block, even when the block then rolls back. Spring calls
 * it before taking that connection, so nothing begins. {@link RefuseSpringTransactionInBlockRegistrar}
 * adds it to every transaction manager of the context.
 */
public final class RefuseSpringTransactionInBlock implements TransactionExecutionListener {

	@Override
	public void beforeBegin(TransactionExecution transaction) {
		if (TransactionScope.isOpen()) {
			throw new IllegalStateException("a PlsqlTransaction is open on this thread, and a Spring transaction,"
					+ " such as a @Transactional method called inside the block, directly or through tx.using, would"
					+ " commit apart from it: in a Spring application, Spring owns the transactions, so do the whole"
					+ " work in one Spring transaction, with @Transactional or TransactionTemplate, or begin it"
					+ " outside the block");
		}
	}
}
