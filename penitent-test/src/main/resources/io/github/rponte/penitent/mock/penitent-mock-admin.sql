-- Penitent mocks, prerequisites for a DBA: run once, on a TEST database only.
--
-- ENABLE EDITIONS cannot be undone. It is limited to packages and standalone routines, which stubs rewrite, and views,
-- which scripts of the tests replace, so tables and object types of the schema are left as they are. Replace
-- ${schema} with the schema that owns the mocked packages; Flyway replaces it by itself, from a placeholder of that
-- name.

ALTER USER ${schema} ENABLE EDITIONS FOR PACKAGE;

-- Views apart: a materialized view reading one of the schema's views makes this fail with ORA-38819, and FORCE would
-- leave that materialized view INVALID, so it is not used. Without editions for views, stubs still work, and a test
-- that applies a script with a view is refused with what to do.
ALTER USER ${schema} ENABLE EDITIONS FOR VIEW;

-- Standalone functions and procedures, which stubs rewrite as a package's routines, each in a statement of its own for
-- the same reason as views: a non-editioned object that depends on one of them makes it fail with ORA-38819.
ALTER USER ${schema} ENABLE EDITIONS FOR FUNCTION;

ALTER USER ${schema} ENABLE EDITIONS FOR PROCEDURE;

-- Synonyms are left out: an object type, a table or a materialized view using one of the schema's private synonyms, a
-- common pattern, makes ENABLE EDITIONS FOR SYNONYM fail with ORA-38819, and FORCE would leave that object INVALID
-- for good. Only a test that applies a script with a synonym needs them; it is refused with what to do, and the DBA,
-- who knows the schema, decides.

GRANT CREATE ANY EDITION, DROP ANY EDITION TO ${schema};
