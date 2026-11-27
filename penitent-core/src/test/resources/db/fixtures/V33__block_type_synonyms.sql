-- More ways a block's @PlsqlType names a type of another schema, as PL/SQL resolves them (#174): a synonym of a synonym
-- to an object type, and a view, for a %ROWTYPE. The public synonyms are in OracleIntegrationTest, created as the
-- administrator: BLOCK_PUB_ADDRESS, to an object type, and BLOCK_PUB_ITEM, to a table.
CREATE SYNONYM BLOCK_CHAIN_ADDRESS FOR OWNER_ADDRESS_SYN;

CREATE VIEW OWNER_ITEM_V AS SELECT id, description FROM plsql_owner.OWNER_ITEM;
