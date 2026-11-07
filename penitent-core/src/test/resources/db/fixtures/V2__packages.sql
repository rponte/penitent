CREATE OR REPLACE PACKAGE FIXTURE_SCALARS AS

	FUNCTION constant RETURN NUMBER;
	FUNCTION plus(p_a NUMBER, p_b NUMBER) RETURN NUMBER;
	FUNCTION echo_number(p_value NUMBER) RETURN NUMBER;
	FUNCTION echo_text(p_value VARCHAR2) RETURN VARCHAR2;
	FUNCTION echo_date(p_value DATE) RETURN DATE;
	FUNCTION echo_timestamp(p_value TIMESTAMP) RETURN TIMESTAMP;
	FUNCTION is_positive(p_value NUMBER) RETURN BOOLEAN;
	FUNCTION negate(p_value BOOLEAN) RETURN BOOLEAN;
	FUNCTION with_default(p_a NUMBER, p_b NUMBER DEFAULT 10) RETURN NUMBER;
	FUNCTION overloaded(p_a NUMBER) RETURN NUMBER;
	FUNCTION overloaded(p_a VARCHAR2, p_b VARCHAR2) RETURN VARCHAR2;

	PROCEDURE register(p_id NUMBER, p_description VARCHAR2);
	PROCEDURE no_arguments;
	PROCEDURE raise_error(p_message VARCHAR2);
	PROCEDURE with_out(p_a NUMBER, p_b OUT NUMBER);

END FIXTURE_SCALARS;
/

CREATE OR REPLACE PACKAGE BODY FIXTURE_SCALARS AS

	FUNCTION constant RETURN NUMBER IS BEGIN RETURN 42; END;
	FUNCTION plus(p_a NUMBER, p_b NUMBER) RETURN NUMBER IS BEGIN RETURN p_a + p_b; END;
	FUNCTION echo_number(p_value NUMBER) RETURN NUMBER IS BEGIN RETURN p_value; END;
	FUNCTION echo_text(p_value VARCHAR2) RETURN VARCHAR2 IS BEGIN RETURN p_value; END;
	FUNCTION echo_date(p_value DATE) RETURN DATE IS BEGIN RETURN p_value; END;
	FUNCTION echo_timestamp(p_value TIMESTAMP) RETURN TIMESTAMP IS BEGIN RETURN p_value; END;
	FUNCTION is_positive(p_value NUMBER) RETURN BOOLEAN IS BEGIN RETURN p_value > 0; END;
	FUNCTION negate(p_value BOOLEAN) RETURN BOOLEAN IS BEGIN RETURN NOT p_value; END;
	FUNCTION with_default(p_a NUMBER, p_b NUMBER DEFAULT 10) RETURN NUMBER IS BEGIN RETURN p_a + p_b; END;
	FUNCTION overloaded(p_a NUMBER) RETURN NUMBER IS BEGIN RETURN p_a; END;
	FUNCTION overloaded(p_a VARCHAR2, p_b VARCHAR2) RETURN VARCHAR2 IS BEGIN RETURN p_a || p_b; END;

	PROCEDURE register(p_id NUMBER, p_description VARCHAR2) IS
	BEGIN
		INSERT INTO FIXTURE_ITEM (ID, DESCRIPTION) VALUES (p_id, p_description);
	END;

	PROCEDURE no_arguments IS BEGIN NULL; END;

	PROCEDURE raise_error(p_message VARCHAR2) IS
	BEGIN
		RAISE_APPLICATION_ERROR(-20100, p_message);
	END;

	PROCEDURE with_out(p_a NUMBER, p_b OUT NUMBER) IS BEGIN p_b := p_a; END;

END FIXTURE_SCALARS;
/

CREATE OR REPLACE PACKAGE FIXTURE_COMPOSITES AS

	TYPE mini_item IS RECORD
	(
		id           NUMBER(10),
		description  VARCHAR2(60),
		quantity     NUMBER(15,2)
	);
	TYPE mini_item_list IS TABLE OF mini_item;
	TYPE item_list IS TABLE OF FIXTURE_ITEM%ROWTYPE;
	TYPE pkg_name_list IS TABLE OF VARCHAR2(60);

	-- the nested shapes a RECORD can have: another record, an OBJECT and both kinds of collection
	TYPE rich_record IS RECORD
	(
		id         NUMBER(10),
		item       mini_item,
		address    ADDRESS_T,
		nicknames  NAME_LIST_T,
		names      pkg_name_list
	);

	FUNCTION echo_address(p_value ADDRESS_T) RETURN ADDRESS_T;
	FUNCTION echo_customer(p_value CUSTOMER_T) RETURN CUSTOMER_T;
	FUNCTION customer_name(p_value CUSTOMER_T) RETURN VARCHAR2;

	FUNCTION echo_addresses(p_value ADDRESS_LIST_T) RETURN ADDRESS_LIST_T;
	FUNCTION null_addresses RETURN ADDRESS_LIST_T;
	FUNCTION echo_names(p_value NAME_LIST_T) RETURN NAME_LIST_T;
	FUNCTION echo_numbers(p_value NUMBER_VARRAY_T) RETURN NUMBER_VARRAY_T;
	FUNCTION echo_names_pkg(p_value pkg_name_list) RETURN pkg_name_list;

	FUNCTION echo_record(p_value mini_item) RETURN mini_item;
	FUNCTION echo_records(p_value mini_item_list) RETURN mini_item_list;
	FUNCTION echo_rowtype(p_value FIXTURE_ITEM%ROWTYPE) RETURN FIXTURE_ITEM%ROWTYPE;
	FUNCTION echo_rowtypes(p_value item_list) RETURN item_list;
	FUNCTION echo_rich_record(p_value rich_record) RETURN rich_record;

	FUNCTION items(p_count NUMBER) RETURN SYS_REFCURSOR;

END FIXTURE_COMPOSITES;
/

CREATE OR REPLACE PACKAGE BODY FIXTURE_COMPOSITES AS

	FUNCTION echo_address(p_value ADDRESS_T) RETURN ADDRESS_T IS BEGIN RETURN p_value; END;
	FUNCTION echo_customer(p_value CUSTOMER_T) RETURN CUSTOMER_T IS BEGIN RETURN p_value; END;
	FUNCTION customer_name(p_value CUSTOMER_T) RETURN VARCHAR2 IS BEGIN RETURN p_value.full_name; END;

	FUNCTION echo_addresses(p_value ADDRESS_LIST_T) RETURN ADDRESS_LIST_T IS BEGIN RETURN p_value; END;
	FUNCTION null_addresses RETURN ADDRESS_LIST_T IS BEGIN RETURN NULL; END;
	FUNCTION echo_names(p_value NAME_LIST_T) RETURN NAME_LIST_T IS BEGIN RETURN p_value; END;
	FUNCTION echo_numbers(p_value NUMBER_VARRAY_T) RETURN NUMBER_VARRAY_T IS BEGIN RETURN p_value; END;
	FUNCTION echo_names_pkg(p_value pkg_name_list) RETURN pkg_name_list IS BEGIN RETURN p_value; END;

	FUNCTION echo_record(p_value mini_item) RETURN mini_item IS BEGIN RETURN p_value; END;
	FUNCTION echo_records(p_value mini_item_list) RETURN mini_item_list IS BEGIN RETURN p_value; END;
	FUNCTION echo_rowtype(p_value FIXTURE_ITEM%ROWTYPE) RETURN FIXTURE_ITEM%ROWTYPE IS BEGIN RETURN p_value; END;
	FUNCTION echo_rowtypes(p_value item_list) RETURN item_list IS BEGIN RETURN p_value; END;
	FUNCTION echo_rich_record(p_value rich_record) RETURN rich_record IS BEGIN RETURN p_value; END;

	FUNCTION items(p_count NUMBER) RETURN SYS_REFCURSOR IS
		l_cursor SYS_REFCURSOR;
	BEGIN
		OPEN l_cursor FOR
			SELECT LEVEL              AS id,
			       'item-' || LEVEL   AS description,
			       LEVEL * 1.5        AS quantity,
			       DATE '2018-01-15'  AS registered_at,
			       'work column'      AS work_column
			  FROM DUAL
			 WHERE p_count > 0 -- CONNECT BY LEVEL <= 0 would still return one row
			CONNECT BY LEVEL <= p_count
			 ORDER BY LEVEL;
		RETURN l_cursor;
	END;

END FIXTURE_COMPOSITES;
/
