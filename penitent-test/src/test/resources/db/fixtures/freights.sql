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

-- a collection of scalars, and an object type with an object type and collections as attributes
CREATE OR REPLACE TYPE freight_tags_t AS TABLE OF VARCHAR2(20)
/

CREATE OR REPLACE TYPE freight_codes_v AS VARRAY(5) OF NUMBER
/

-- an object type with a DATE and a TIMESTAMP
CREATE OR REPLACE TYPE freight_event_t AS OBJECT (
  id         NUMBER,
  happened   DATE,
  stamped_at TIMESTAMP
)
/

CREATE OR REPLACE TYPE freight_shipment_t AS OBJECT (
  code  VARCHAR2(10),
  item  freight_item_t,
  items freight_item_list_t,
  tags  freight_tags_t,
  codes freight_codes_v
)
/

-- a view, which a test replaces in its edition
CREATE OR REPLACE VIEW freight_items_v AS
  SELECT id, uf FROM freight_item
/

-- exceptions a package declares, which a stub raises and a caller catches by name
CREATE OR REPLACE PACKAGE freight_errors AS
  no_quote EXCEPTION;
END freight_errors;
/

CREATE OR REPLACE PACKAGE freights AS
  SUBTYPE money IS NUMBER(10,2);
  unknown_uf EXCEPTION;
  TYPE freight_rows_t IS TABLE OF freight_item%ROWTYPE;
  TYPE freight_ids_t IS TABLE OF NUMBER INDEX BY PLS_INTEGER;

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
  FUNCTION find(p_id NUMBER) RETURN freight_item%ROWTYPE;
  FUNCTION count_rows(p_rows freight_rows_t) RETURN NUMBER;
  FUNCTION rows_of(p_count NUMBER) RETURN freight_rows_t;
  FUNCTION describe_region(p_region plsql_registry.region%ROWTYPE) RETURN VARCHAR2;
  FUNCTION item_of(p_id NUMBER) RETURN freight_item_t;
  FUNCTION items_of(p_uf VARCHAR2) RETURN freight_item_list_t;
  FUNCTION shipment_of(p_code VARCHAR2) RETURN freight_shipment_t;
  FUNCTION tags_of(p_uf VARCHAR2) RETURN freight_tags_t;
  PROCEDURE load_item(p_id NUMBER, p_item OUT freight_item_t);
  FUNCTION count_items_of(p_uf VARCHAR2) RETURN NUMBER;
  FUNCTION ids_of(p_uf VARCHAR2) RETURN freight_ids_t;
  FUNCTION event_of(p_id NUMBER) RETURN freight_event_t;
  FUNCTION count_view_items(p_uf VARCHAR2) RETURN NUMBER;
  FUNCTION describe_flag(p_flag BOOLEAN) RETURN VARCHAR2;
  PROCEDURE integrate(p_operation NUMBER, p_name VARCHAR2);
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

  -- give back records, and take and give back collections of them
  FUNCTION find(p_id NUMBER) RETURN freight_item%ROWTYPE IS
    l_row freight_item%ROWTYPE;
  BEGIN
    l_row.id := p_id;
    l_row.uf := 'CE';
    RETURN l_row;
  END find;

  FUNCTION count_rows(p_rows freight_rows_t) RETURN NUMBER IS
  BEGIN
    RETURN p_rows.COUNT;
  END count_rows;

  FUNCTION rows_of(p_count NUMBER) RETURN freight_rows_t IS
    l_rows freight_rows_t := freight_rows_t();
  BEGIN
    FOR i IN 1 .. p_count LOOP
      l_rows.EXTEND;
      l_rows(i).id := i;
      l_rows(i).uf := 'SP';
    END LOOP;
    RETURN l_rows;
  END rows_of;

  -- takes a row of a table of another schema, which has no editions
  FUNCTION describe_region(p_region plsql_registry.region%ROWTYPE) RETURN VARCHAR2 IS
  BEGIN
    RETURN p_region.id || '/' || p_region.uf;
  END describe_region;

  -- give back object types and collections, which stubs answer with
  FUNCTION item_of(p_id NUMBER) RETURN freight_item_t IS
  BEGIN
    RETURN freight_item_t(p_id, 'XX');
  END item_of;

  FUNCTION items_of(p_uf VARCHAR2) RETURN freight_item_list_t IS
  BEGIN
    RETURN freight_item_list_t(freight_item_t(1, p_uf));
  END items_of;

  FUNCTION shipment_of(p_code VARCHAR2) RETURN freight_shipment_t IS
  BEGIN
    RETURN freight_shipment_t(p_code, NULL, NULL, NULL, NULL);
  END shipment_of;

  FUNCTION tags_of(p_uf VARCHAR2) RETURN freight_tags_t IS
  BEGIN
    RETURN freight_tags_t(p_uf);
  END tags_of;

  PROCEDURE load_item(p_id NUMBER, p_item OUT freight_item_t) IS
  BEGIN
    p_item := freight_item_t(p_id, 'XX');
  END load_item;

  -- calls items_of from PL/SQL
  FUNCTION count_items_of(p_uf VARCHAR2) RETURN NUMBER IS
  BEGIN
    RETURN items_of(p_uf).COUNT;
  END count_items_of;

  FUNCTION ids_of(p_uf VARCHAR2) RETURN freight_ids_t IS
    l_ids freight_ids_t;
  BEGIN
    l_ids(1) := 1;
    RETURN l_ids;
  END ids_of;

  FUNCTION event_of(p_id NUMBER) RETURN freight_event_t IS
  BEGIN
    RETURN freight_event_t(p_id, NULL, NULL);
  END event_of;

  -- reads the view
  FUNCTION count_view_items(p_uf VARCHAR2) RETURN NUMBER IS
    l_count NUMBER;
  BEGIN
    SELECT COUNT(*) INTO l_count FROM freight_items_v WHERE uf = p_uf;
    RETURN l_count;
  END count_view_items;

  -- take primitives in Java: a BOOLEAN, and a number with a name
  FUNCTION describe_flag(p_flag BOOLEAN) RETURN VARCHAR2 IS
  BEGIN
    RETURN CASE WHEN p_flag THEN 'yes' ELSE 'no' END;
  END describe_flag;

  PROCEDURE integrate(p_operation NUMBER, p_name VARCHAR2) IS
  BEGIN
    NULL;
  END integrate;
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

-- catches, by name, the exceptions freights.calculate may raise
CREATE OR REPLACE PACKAGE safe_checkout AS
  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;
END safe_checkout;
/

CREATE OR REPLACE PACKAGE BODY safe_checkout AS
  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
  BEGIN
    RETURN 100 + freights.calculate(p_origin, p_destination);
  EXCEPTION
    WHEN freights.unknown_uf THEN RETURN -1;
    WHEN freight_errors.no_quote THEN RETURN -2;
  END total;
END safe_checkout;
/

-- standalone routines, outside any package: their real code fails, so a test passes only through a stub
CREATE OR REPLACE FUNCTION tax_rate(p_uf VARCHAR2) RETURN NUMBER IS
BEGIN
  RAISE_APPLICATION_ERROR(-20400, 'the real tax_rate reads a table the tests do not have');
END tax_rate;
/

CREATE OR REPLACE PROCEDURE notify_tax_office(p_invoice NUMBER) IS
BEGIN
  RAISE_APPLICATION_ERROR(-20401, 'the real notify_tax_office calls the tax office');
END notify_tax_office;
/

CREATE OR REPLACE PROCEDURE quote_tax(p_uf IN VARCHAR2, p_rate OUT NUMBER) IS
BEGIN
  RAISE_APPLICATION_ERROR(-20402, 'the real quote_tax calls the tax office');
END quote_tax;
/

-- calls tax_rate from PL/SQL
CREATE OR REPLACE FUNCTION invoice_total(p_value NUMBER, p_uf VARCHAR2) RETURN NUMBER IS
BEGIN
  RETURN p_value * (1 + tax_rate(p_uf));
END invoice_total;
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

-- a standalone function whose source is wrapped
BEGIN
  DBMS_DDL.CREATE_WRAPPED(q'[
    CREATE OR REPLACE FUNCTION wrapped_rate RETURN NUMBER IS BEGIN RETURN 1; END wrapped_rate;]');
END;
/

-- records, %ROWTYPE and INDEX BY that stubs answer with; the real routines fail, so a test passes only through a stub
CREATE TABLE freight_note (
  id NUMBER PRIMARY KEY,
  "Note Text" VARCHAR2(30)
)
/

-- tables whose names need quotes: mixed case, and a space
CREATE TABLE "Crate" (id NUMBER PRIMARY KEY, uf VARCHAR2(2))
/

CREATE TABLE "Freight Box" (id NUMBER PRIMARY KEY, uf VARCHAR2(2))
/

-- a view, whose %ROWTYPE a stub returns
CREATE OR REPLACE VIEW freight_item_v AS SELECT id, uf FROM freight_item
/

CREATE OR REPLACE PACKAGE freight_records AS
  TYPE quote_t IS RECORD (uf VARCHAR2(2), value NUMBER);
  TYPE leg_t IS RECORD (origin VARCHAR2(2), quote quote_t);
  TYPE quotes_t IS TABLE OF quote_t;
  TYPE quotes_by_index_t IS TABLE OF quote_t INDEX BY PLS_INTEGER;
  TYPE ufs_t IS TABLE OF VARCHAR2(2) INDEX BY PLS_INTEGER;
  TYPE ufs_by_key_t IS TABLE OF VARCHAR2(2) INDEX BY VARCHAR2(2);
  TYPE route_t IS RECORD (name VARCHAR2(10), ufs ufs_t);

  FUNCTION quote_of(p_uf VARCHAR2) RETURN quote_t;
  PROCEDURE quote(p_uf VARCHAR2, p_quote OUT quote_t);
  PROCEDURE settle(p_uf VARCHAR2, p_day DATE, p_quote OUT quote_t);
  PROCEDURE settle_later(p_uf VARCHAR2, p_day DATE DEFAULT SYSDATE, p_quote OUT quote_t);
  FUNCTION leg_of(p_origin VARCHAR2) RETURN leg_t;
  FUNCTION quotes_of(p_uf VARCHAR2) RETURN quotes_t;
  FUNCTION quotes_by_index(p_uf VARCHAR2) RETURN quotes_by_index_t;
  FUNCTION ufs RETURN ufs_t;
  FUNCTION ufs_by_key RETURN ufs_by_key_t;
  FUNCTION item_of(p_id NUMBER) RETURN freight_item%ROWTYPE;
  PROCEDURE load_item(p_id NUMBER, p_item OUT freight_item%ROWTYPE);
  FUNCTION note_of(p_id NUMBER) RETURN freight_note%ROWTYPE;
  -- calls quote_of and item_of, as PL/SQL that uses a stub would
  FUNCTION describe(p_uf VARCHAR2, p_id NUMBER) RETURN VARCHAR2;
  -- a %ROWTYPE of a table whose name needs quotes: the describe ones are real, the others fail
  FUNCTION describe_crate(p_crate "Crate"%ROWTYPE) RETURN VARCHAR2;
  FUNCTION crate_of(p_id NUMBER) RETURN "Crate"%ROWTYPE;
  FUNCTION describe_box(p_box "Freight Box"%ROWTYPE) RETURN VARCHAR2;
  FUNCTION box_of(p_id NUMBER) RETURN "Freight Box"%ROWTYPE;
  -- a %ROWTYPE of a view
  FUNCTION item_view_of(p_id NUMBER) RETURN freight_item_v%ROWTYPE;
  -- a RECORD holding an INDEX BY
  FUNCTION route_of(p_name VARCHAR2) RETURN route_t;
END freight_records;
/

CREATE OR REPLACE PACKAGE BODY freight_records AS
  FUNCTION quote_of(p_uf VARCHAR2) RETURN quote_t IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real quote_of');
  END quote_of;

  PROCEDURE quote(p_uf VARCHAR2, p_quote OUT quote_t) IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real quote');
  END quote;

  PROCEDURE settle(p_uf VARCHAR2, p_day DATE, p_quote OUT quote_t) IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real settle');
  END settle;

  PROCEDURE settle_later(p_uf VARCHAR2, p_day DATE DEFAULT SYSDATE, p_quote OUT quote_t) IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real settle_later');
  END settle_later;

  FUNCTION leg_of(p_origin VARCHAR2) RETURN leg_t IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real leg_of');
  END leg_of;

  FUNCTION quotes_of(p_uf VARCHAR2) RETURN quotes_t IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real quotes_of');
  END quotes_of;

  FUNCTION quotes_by_index(p_uf VARCHAR2) RETURN quotes_by_index_t IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real quotes_by_index');
  END quotes_by_index;

  FUNCTION ufs RETURN ufs_t IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real ufs');
  END ufs;

  FUNCTION ufs_by_key RETURN ufs_by_key_t IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real ufs_by_key');
  END ufs_by_key;

  FUNCTION item_of(p_id NUMBER) RETURN freight_item%ROWTYPE IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real item_of');
  END item_of;

  PROCEDURE load_item(p_id NUMBER, p_item OUT freight_item%ROWTYPE) IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real load_item');
  END load_item;

  FUNCTION note_of(p_id NUMBER) RETURN freight_note%ROWTYPE IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real note_of');
  END note_of;

  FUNCTION describe(p_uf VARCHAR2, p_id NUMBER) RETURN VARCHAR2 IS
    l_quote quote_t := quote_of(p_uf);
    l_item freight_item%ROWTYPE := item_of(p_id);
  BEGIN
    RETURN l_quote.uf || '=' || l_quote.value || ', ' || l_item.id || '/' || l_item.uf;
  END describe;

  FUNCTION describe_crate(p_crate "Crate"%ROWTYPE) RETURN VARCHAR2 IS
  BEGIN
    RETURN p_crate.id || '/' || p_crate.uf;
  END describe_crate;

  FUNCTION crate_of(p_id NUMBER) RETURN "Crate"%ROWTYPE IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real crate_of');
  END crate_of;

  FUNCTION describe_box(p_box "Freight Box"%ROWTYPE) RETURN VARCHAR2 IS
  BEGIN
    RETURN p_box.id || '/' || p_box.uf;
  END describe_box;

  FUNCTION box_of(p_id NUMBER) RETURN "Freight Box"%ROWTYPE IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real box_of');
  END box_of;

  FUNCTION item_view_of(p_id NUMBER) RETURN freight_item_v%ROWTYPE IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real item_view_of');
  END item_view_of;

  FUNCTION route_of(p_name VARCHAR2) RETURN route_t IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20410, 'the real route_of');
  END route_of;
END freight_records;
/

-- scalars a stub returns directly: a BOOLEAN, a TIMESTAMP and a DATE; the real routines fail
CREATE OR REPLACE PACKAGE freight_scalars AS
  FUNCTION is_open(p_uf VARCHAR2) RETURN BOOLEAN;
  FUNCTION shipped_at(p_id NUMBER) RETURN TIMESTAMP;
  FUNCTION due_on(p_id NUMBER) RETURN DATE;
  -- calls is_open, as PL/SQL that uses a stub would
  FUNCTION status_of(p_uf VARCHAR2) RETURN VARCHAR2;
END freight_scalars;
/

CREATE OR REPLACE PACKAGE BODY freight_scalars AS
  FUNCTION is_open(p_uf VARCHAR2) RETURN BOOLEAN IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20420, 'the real is_open');
  END is_open;

  FUNCTION shipped_at(p_id NUMBER) RETURN TIMESTAMP IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20420, 'the real shipped_at');
  END shipped_at;

  FUNCTION due_on(p_id NUMBER) RETURN DATE IS
  BEGIN
    RAISE_APPLICATION_ERROR(-20420, 'the real due_on');
  END due_on;

  FUNCTION status_of(p_uf VARCHAR2) RETURN VARCHAR2 IS
  BEGIN
    RETURN CASE WHEN is_open(p_uf) THEN 'open' WHEN NOT is_open(p_uf) THEN 'closed' ELSE 'unknown' END;
  END status_of;
END freight_scalars;
/
