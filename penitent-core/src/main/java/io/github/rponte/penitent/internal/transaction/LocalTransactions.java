package io.github.rponte.penitent.internal.transaction;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import io.github.rponte.penitent.PlsqlSettings;
import io.github.rponte.penitent.PlsqlTransactions;
import io.github.rponte.penitent.exception.DefaultPlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlExceptionTranslator;
import io.github.rponte.penitent.exception.PlsqlFailure;
import io.github.rponte.penitent.internal.proxy.TransactionScope;

/**
 * {@link PlsqlTransactions} on JDBC alone: one connection per block, auto-commit off while it runs,
 * commit or rollback at its end, and the connection given back as it was found.
 *
 * <p>The block's own failure always wins: a failure to roll back, restore auto-commit or close is
 * suppressed into it. Only when the block succeeded does a failure of the commit, or of the cleanup,
 * reach the caller, translated with the {@code TRANSACTION} phase.
 *
 * <p>Before taking a connection, each {@link TransactionIntegration} may refuse the block, as
 * {@code penitent-spring} does inside a Spring transaction.
 */
public final class LocalTransactions implements PlsqlTransactions {

	private final DataSource dataSource;
	private final PlsqlExceptionTranslator translator;

	public LocalTransactions(DataSource dataSource, PlsqlSettings settings) {
		this.dataSource = dataSource;
		this.translator = settings.exceptionTranslator().orElseGet(DefaultPlsqlExceptionTranslator::new);
	}

	@Override
	public <E extends Exception> void run(Work<E> work) throws E {
		runWithResult(tx -> {
			work.run(tx);
			return null;
		});
	}

	@Override
	public <T, E extends Exception> T runWithResult(WorkWithResult<T, E> work) throws E {
		if (TransactionScope.isOpen()) {
			throw new IllegalStateException("a PlsqlTransaction is already open on this thread; nested blocks are not"
					+ " supported: pass the PlsqlTransaction you have instead of opening another");
		}
		TransactionIntegration.all().forEach(TransactionIntegration::refuseBlock);
		Connection connection = begin();
		boolean autoCommit;
		try {
			autoCommit = connection.getAutoCommit();
			if (autoCommit) {
				connection.setAutoCommit(false);
			}
		} catch (SQLException e) {
			Throwable failure = translate(e);
			close(connection, failure);
			throw sneaky(failure);
		}

		ActiveTransaction tx = new ActiveTransaction(connection, Thread.currentThread());
		Throwable failure = null;
		T result = null;
		TransactionScope.open(dataSource);
		try {
			result = work.run(tx);
		} catch (Throwable thrown) {
			failure = thrown;
		} finally {
			tx.end();
			TransactionScope.close();
		}

		failure = finish(connection, tx.isRollbackOnly() || failure != null, failure);
		failure = cleanup(connection, autoCommit, failure);
		if (failure != null) {
			throw sneaky(failure);
		}
		return result;
	}

	private Connection begin() {
		try {
			return dataSource.getConnection();
		} catch (SQLException e) {
			throw translator.translate(new PlsqlFailure(null, e, PlsqlFailure.Phase.CONNECTION));
		}
	}

	/** Commits, or rolls back; a failed commit is rolled back too, and becomes the failure. */
	private Throwable finish(Connection connection, boolean rollback, Throwable failure) {
		if (!rollback) {
			try {
				connection.commit();
				return null;
			} catch (SQLException e) {
				failure = translate(e);
			}
		}
		try {
			connection.rollback();
		} catch (SQLException e) {
			failure = suppressOrBecome(failure, e);
		}
		return failure;
	}

	/** Gives the connection back as it was found: auto-commit restored, then closed. */
	private Throwable cleanup(Connection connection, boolean autoCommit, Throwable failure) {
		if (autoCommit) {
			try {
				connection.setAutoCommit(true);
			} catch (SQLException e) {
				failure = suppressOrBecome(failure, e);
			}
		}
		return close(connection, failure);
	}

	private Throwable close(Connection connection, Throwable failure) {
		try {
			connection.close();
		} catch (SQLException e) {
			failure = suppressOrBecome(failure, e);
		}
		return failure;
	}

	private Throwable suppressOrBecome(Throwable failure, SQLException e) {
		if (failure == null) {
			return translate(e);
		}
		failure.addSuppressed(e);
		return failure;
	}

	private RuntimeException translate(SQLException e) {
		return translator.translate(new PlsqlFailure(null, e, PlsqlFailure.Phase.TRANSACTION));
	}

	/** Throws {@code failure} as it is, checked or not: it is what the block threw, or a translated one. */
	@SuppressWarnings("unchecked")
	private static <X extends Throwable> RuntimeException sneaky(Throwable failure) throws X {
		throw (X) failure;
	}
}
