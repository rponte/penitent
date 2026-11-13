-- The fixtures of penitent-test, and of its examples on the Testing page, which shows some of these lines.
-- Statements end with a line holding only a slash.

CREATE TABLE freight_item (
  id NUMBER PRIMARY KEY,
  uf VARCHAR2(2)
)
/

-- an object type and a collection of it, for routines that take them
CREATE OR REPLACE TYPE freight_item_t AS OBJECT (
  id NUMBER,
  uf VARCHAR2(2)
)
/

CREATE OR REPLACE TYPE freight_item_list_t AS TABLE OF freight_item_t
/

CREATE OR REPLACE PACKAGE freights AS
  SUBTYPE money IS NUMBER(10,2);

  FUNCTION calculate(p_origin freight_item.uf%TYPE, p_destination VARCHAR2 DEFAULT 'SP') RETURN money;
  FUNCTION calculate(p_weight NUMBER) RETURN NUMBER;
  PROCEDURE quote(p_uf IN VARCHAR2, p_value OUT money);
  FUNCTION count_items(p_uf VARCHAR2) RETURN NUMBER;
  FUNCTION label(p_day DATE) RETURN VARCHAR2;
  FUNCTION real_one RETURN VARCHAR2;
  FUNCTION calls RETURN PLS_INTEGER;
  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
  FUNCTION weigh(p_items freight_item_list_t, p_origin VARCHAR2) RETURN NUMBER;
  PROCEDURE register(p_item freight_item_t);
  FUNCTION describe(p_row freight_item%ROWTYPE) RETURN VARCHAR2;
END freights;
/

CREATE OR REPLACE PACKAGE BODY freights AS
  g_calls PLS_INTEGER := 0;

  -- private, used by the real routines
  FUNCTION base_rate(p_uf VARCHAR2) RETURN NUMBER IS
  BEGIN
    RETURN CASE p_uf WHEN 'CE' THEN 50 ELSE 10 END;
  END base_rate;

  FUNCTION calculate(p_origin freight_item.uf%TYPE, p_destination VARCHAR2 DEFAULT 'SP') RETURN money IS
    l_value money;
  BEGIN
    g_calls := g_calls + 1;
    l_value := base_rate(p_destination) * 1.5;
    RETURN l_value;
  END calculate;

  FUNCTION calculate(p_weight NUMBER) RETURN NUMBER IS
  BEGIN
    g_calls := g_calls + 1;
    RETURN p_weight * 2;
  END calculate;

  PROCEDURE quote(p_uf IN VARCHAR2, p_value OUT money) AS
  BEGIN
    p_value := base_rate(p_uf);
  END;

  -- reads the schema's data, as most routines do
  FUNCTION count_items(p_uf VARCHAR2) RETURN NUMBER IS
    l_count NUMBER;
  BEGIN
    SELECT COUNT(*) INTO l_count FROM freight_item WHERE uf = p_uf;
    RETURN l_count;
  END count_items;

  FUNCTION label(p_day DATE) RETURN VARCHAR2 IS
  BEGIN
    RETURN TO_CHAR(p_day, 'YYYY-MM-DD');
  END label;

  FUNCTION real_one RETURN VARCHAR2 IS BEGIN RETURN 'REAL'; END;

  FUNCTION calls RETURN PLS_INTEGER IS BEGIN RETURN g_calls; END calls;

  -- calls another routine of the same package
  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
  BEGIN
    RETURN 100 + calculate(p_origin, p_destination);
  END total;

  -- take an object type, a collection and a record
  FUNCTION weigh(p_items freight_item_list_t, p_origin VARCHAR2) RETURN NUMBER IS
  BEGIN
    RETURN p_items.COUNT;
  END weigh;

  PROCEDURE register(p_item freight_item_t) IS
  BEGIN
    INSERT INTO freight_item (id, uf) VALUES (p_item.id, p_item.uf);
  END register;

  FUNCTION describe(p_row freight_item%ROWTYPE) RETURN VARCHAR2 IS
  BEGIN
    RETURN p_row.id || '/' || p_row.uf;
  END describe;
END freights;
/

CREATE OR REPLACE PACKAGE checkout AS
  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
END checkout;
/

CREATE OR REPLACE PACKAGE BODY checkout AS
  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
  BEGIN
    RETURN 100 + freights.calculate(p_origin, p_destination);
  END total;
END checkout;
/

-- a package whose body is wrapped, which the mocks cannot read
BEGIN
  DBMS_DDL.CREATE_WRAPPED(q'[
    CREATE OR REPLACE PACKAGE wrapped_one AS FUNCTION secret RETURN NUMBER; END wrapped_one;]');
  DBMS_DDL.CREATE_WRAPPED(q'[
    CREATE OR REPLACE PACKAGE BODY wrapped_one AS
      FUNCTION secret RETURN NUMBER IS BEGIN RETURN 42; END secret;
    END wrapped_one;]');
END;
/
