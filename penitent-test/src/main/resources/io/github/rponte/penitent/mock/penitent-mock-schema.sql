-- Penitent mocks, prerequisites for the schema owner: run once, as the owner of the mocked packages, after
-- penitent-mock-admin.sql. Each mocked routine records its calls through PENITENT_MOCK, for verify().
--
-- PENITENT_MOCK.VERSION tells the library which version of this script is applied: when it changes, the library
-- asks for the script to be applied again.

CREATE TABLE PENITENT_MOCK_CALLS (
  EDITION_NAME VARCHAR2(128) NOT NULL,
  ROUTINE      VARCHAR2(512) NOT NULL,
  ARGS         VARCHAR2(4000),
  CALLED_AT    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE OR REPLACE PACKAGE PENITENT_MOCK AS
  FUNCTION version RETURN NUMBER;
  PROCEDURE record(p_routine VARCHAR2, p_args VARCHAR2);
END PENITENT_MOCK;
/

CREATE OR REPLACE PACKAGE BODY PENITENT_MOCK AS
  FUNCTION version RETURN NUMBER IS
  BEGIN
    RETURN 1;
  END version;

  -- its own transaction, so that a call is recorded even when the caller then rolls back
  PROCEDURE record(p_routine VARCHAR2, p_args VARCHAR2) IS
    PRAGMA AUTONOMOUS_TRANSACTION;
  BEGIN
    INSERT INTO PENITENT_MOCK_CALLS (EDITION_NAME, ROUTINE, ARGS)
    VALUES (SYS_CONTEXT('USERENV', 'CURRENT_EDITION_NAME'), p_routine, p_args);
    COMMIT;
  END record;
END PENITENT_MOCK;
/
