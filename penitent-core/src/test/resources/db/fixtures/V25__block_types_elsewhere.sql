--
-- Private synonyms for types and a table of plsql_owner, which a block's @PlsqlType names as PL/SQL would: an object
-- type, a collection and a table, whose %ROWTYPE a block takes. A package of plsql_owner already has one,
-- FIXTURE_OWNER_SYNONYM, in V4.
--
CREATE SYNONYM OWNER_ADDRESS_SYN FOR plsql_owner.OWNER_ADDRESS_T;

CREATE SYNONYM OWNER_ADDRESSES_SYN FOR plsql_owner.OWNER_ADDRESS_LIST_T;

CREATE SYNONYM OWNER_ITEM_SYN FOR plsql_owner.OWNER_ITEM;
