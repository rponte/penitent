--
-- Routines that write, for TransactionsTest: what a call commits, what a failure undoes, and which
-- connection each call uses. Each test writes under its own label, since what is committed stays.
--

CREATE TABLE TX_EVENT (
	ID     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	LABEL  VARCHAR2(100) NOT NULL
);

CREATE OR REPLACE PACKAGE TX_PKG AS

	PROCEDURE record_event(p_label VARCHAR2);
	PROCEDURE record_and_fail(p_label VARCHAR2);
	FUNCTION count_events(p_label VARCHAR2) RETURN NUMBER;
	PROCEDURE count_out(p_label VARCHAR2, p_count OUT NUMBER);
	FUNCTION events(p_label VARCHAR2) RETURN SYS_REFCURSOR;
	FUNCTION session_id RETURN NUMBER;

END TX_PKG;
/

CREATE OR REPLACE PACKAGE BODY TX_PKG AS

	PROCEDURE record_event(p_label VARCHAR2) IS
	BEGIN
		INSERT INTO TX_EVENT (LABEL) VALUES (p_label);
	END;

	-- writes, then fails: what the failure undoes is the question
	PROCEDURE record_and_fail(p_label VARCHAR2) IS
	BEGIN
		INSERT INTO TX_EVENT (LABEL) VALUES (p_label);
		RAISE_APPLICATION_ERROR(-20010, 'failed after writing ' || p_label);
	END;

	FUNCTION count_events(p_label VARCHAR2) RETURN NUMBER IS
		l_count NUMBER;
	BEGIN
		SELECT COUNT(*) INTO l_count FROM TX_EVENT WHERE LABEL = p_label;
		RETURN l_count;
	END;

	PROCEDURE count_out(p_label VARCHAR2, p_count OUT NUMBER) IS
	BEGIN
		p_count := count_events(p_label);
	END;

	FUNCTION events(p_label VARCHAR2) RETURN SYS_REFCURSOR IS
		l_cursor SYS_REFCURSOR;
	BEGIN
		OPEN l_cursor FOR SELECT ID, LABEL FROM TX_EVENT WHERE LABEL = p_label ORDER BY ID;
		RETURN l_cursor;
	END;

	FUNCTION session_id RETURN NUMBER IS
	BEGIN
		RETURN SYS_CONTEXT('USERENV', 'SID');
	END;

END TX_PKG;
/
