-- Penitent mocks, prerequisites for a DBA: run once, on a TEST database only.
--
-- ENABLE EDITIONS cannot be undone. It is limited to packages, which stubs rewrite, and views, which scripts of the
-- tests replace, so tables and object types of the schema are left as they are. Replace ${schema} with the schema
-- that owns the mocked packages; Flyway replaces it by itself, from a placeholder of that name.

ALTER USER ${schema} ENABLE EDITIONS FOR PACKAGE;

-- Views apart: a materialized view reading one of the schema's views makes this fail with ORA-38819, and FORCE would
-- leave that materialized view INVALID, so it is not used. Without editions for views, stubs still work, and a test
-- that applies a script with a view is refused with what to do.
ALTER USER ${schema} ENABLE EDITIONS FOR VIEW;

GRANT CREATE ANY EDITION, DROP ANY EDITION TO ${schema};
