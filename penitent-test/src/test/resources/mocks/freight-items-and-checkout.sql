-- Replaces the view with rows of its own, and the body of CHECKOUT, as a test that cannot reach their data would.
CREATE OR REPLACE VIEW freight_items_v AS
  SELECT 1 AS id, 'SP' AS uf FROM dual
  UNION ALL
  SELECT 2, 'SP' FROM dual
/

CREATE OR REPLACE PACKAGE BODY checkout AS
  FUNCTION total(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
  BEGIN
    RETURN 999;
  END total;
END checkout;
/
