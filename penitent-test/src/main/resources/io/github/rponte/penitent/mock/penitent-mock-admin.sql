-- Penitent mocks, prerequisites for a DBA: run once, on a TEST database only.
--
-- ENABLE EDITIONS cannot be undone. It is limited to packages, the only objects the mocks redefine, so tables and
-- object types of the schema are left as they are. Replace ${schema} with the schema that owns the mocked packages;
-- Flyway replaces it by itself, from a placeholder of that name.

ALTER USER ${schema} ENABLE EDITIONS FOR PACKAGE;

GRANT CREATE ANY EDITION, DROP ANY EDITION TO ${schema};
