--
-- For PlsqlTransactionsTest: a constraint checked only at commit, so that a commit fails for real.
--

CREATE TABLE TX_DEFERRED (
	VALUE  NUMBER CONSTRAINT TX_DEFERRED_POSITIVE CHECK (VALUE > 0) DEFERRABLE INITIALLY DEFERRED
);
