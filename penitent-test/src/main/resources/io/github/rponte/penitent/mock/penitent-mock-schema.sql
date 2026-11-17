-- Penitent mocks, prerequisites for the schema owner: run once, as the owner of the mocked packages, after
-- penitent-mock-admin.sql. Each mocked routine records its calls through PENITENT_MOCK, for verify().
--
-- PENITENT_MOCK.VERSION tells the library which version of this script is applied: when it changes, the library
-- asks for the script to be applied again.

-- version 1 kept the arguments in a VARCHAR2(4000), which a long text or a LOB overflows: a table of that version is
-- dropped, to be created again. It holds only the calls of tests, which each test drops when it ends.
DECLARE
  l_type USER_TAB_COLUMNS.DATA_TYPE%TYPE;
BEGIN
  SELECT DATA_TYPE INTO l_type FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'PENITENT_MOCK_CALLS' AND COLUMN_NAME = 'ARGS';
  IF l_type <> 'CLOB' THEN
    EXECUTE IMMEDIATE 'DROP TABLE PENITENT_MOCK_CALLS';
  END IF;
EXCEPTION
  WHEN NO_DATA_FOUND THEN
    NULL;
END;
/

CREATE TABLE PENITENT_MOCK_CALLS (
  EDITION_NAME VARCHAR2(128) NOT NULL,
  ROUTINE      VARCHAR2(512) NOT NULL,
  ARGS         CLOB,
  CALLED_AT    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE OR REPLACE PACKAGE PENITENT_MOCK AS
  FUNCTION version RETURN NUMBER;
  PROCEDURE record(p_routine VARCHAR2, p_args CLOB);
END PENITENT_MOCK;
/

CREATE OR REPLACE PACKAGE BODY PENITENT_MOCK AS
  FUNCTION version RETURN NUMBER IS
  BEGIN
    RETURN 2;
  END version;

  -- its own transaction, so that a call is recorded even when the caller then rolls back
  PROCEDURE record(p_routine VARCHAR2, p_args CLOB) IS
    PRAGMA AUTONOMOUS_TRANSACTION;
  BEGIN
    INSERT INTO PENITENT_MOCK_CALLS (EDITION_NAME, ROUTINE, ARGS)
    VALUES (SYS_CONTEXT('USERENV', 'CURRENT_EDITION_NAME'), p_routine, p_args);
    COMMIT;
  END record;
END PENITENT_MOCK;
/
