-- freight items of the test's own, in place of the real ones
CREATE OR REPLACE VIEW freight_items_v AS
  SELECT 1 AS id, 'SP' AS uf FROM dual
  UNION ALL
  SELECT 2, 'SP' FROM dual;
