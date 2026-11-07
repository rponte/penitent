CREATE OR REPLACE PACKAGE FIXTURE_OUT AS

	TYPE entry IS RECORD (ID NUMBER(10), DESCRIPTION VARCHAR2(60));
	TYPE pkg_name_list IS TABLE OF VARCHAR2(60);

	PROCEDURE plus(p_a NUMBER, p_b NUMBER, p_total OUT NUMBER);
	PROCEDURE twice(p_value IN OUT NUMBER);
	PROCEDURE several(p_input NUMBER, p_doubled OUT NUMBER, p_text OUT VARCHAR2, p_accumulated IN OUT NUMBER);
	FUNCTION double_and_return(p_a NUMBER, p_doubled OUT NUMBER) RETURN VARCHAR2;

	PROCEDURE out_text(p_value OUT VARCHAR2);
	PROCEDURE out_null(p_value OUT NUMBER);
	PROCEDURE out_date(p_value OUT DATE);
	PROCEDURE out_timestamp(p_value OUT TIMESTAMP);
	PROCEDURE out_binary_double(p_value OUT BINARY_DOUBLE);
	PROCEDURE out_boolean(p_value OUT BOOLEAN);
	PROCEDURE invert_boolean(p_value IN OUT BOOLEAN);
	PROCEDURE invert_sn(p_value IN OUT VARCHAR2);
	PROCEDURE out_invalid_number(p_value OUT VARCHAR2);

	PROCEDURE out_object(p_value OUT ADDRESS_T);
	PROCEDURE change_object(p_value IN OUT ADDRESS_T);
	PROCEDURE out_record(p_value OUT entry);
	PROCEDURE change_record(p_value IN OUT entry);
	PROCEDURE out_rowtype(p_value OUT FIXTURE_ITEM%ROWTYPE);

	PROCEDURE out_names(p_value OUT NAME_LIST_T);
	PROCEDURE change_names(p_value IN OUT NAME_LIST_T);
	PROCEDURE out_addresses(p_value OUT ADDRESS_LIST_T);
	PROCEDURE out_names_pkg(p_value OUT pkg_name_list);
	PROCEDURE out_cursor(p_value OUT SYS_REFCURSOR);

	PROCEDURE assign_and_fail(p_value IN OUT NUMBER);

	PROCEDURE overloaded(p_value NUMBER);
	PROCEDURE overloaded(p_value OUT VARCHAR2);

END FIXTURE_OUT;
/

CREATE OR REPLACE PACKAGE BODY FIXTURE_OUT AS

	PROCEDURE plus(p_a NUMBER, p_b NUMBER, p_total OUT NUMBER) IS BEGIN p_total := p_a + p_b; END;
	PROCEDURE twice(p_value IN OUT NUMBER) IS BEGIN p_value := p_value * 2; END;

	PROCEDURE several(p_input NUMBER, p_doubled OUT NUMBER, p_text OUT VARCHAR2, p_accumulated IN OUT NUMBER) IS
	BEGIN
		p_doubled := p_input * 2;
		p_text := 'input ' || p_input;
		p_accumulated := p_accumulated + p_input;
	END;

	FUNCTION double_and_return(p_a NUMBER, p_doubled OUT NUMBER) RETURN VARCHAR2 IS
	BEGIN
		p_doubled := p_a * 2;
		RETURN 'result ' || p_a;
	END;

	PROCEDURE out_text(p_value OUT VARCHAR2) IS BEGIN p_value := 'café'; END;
	PROCEDURE out_null(p_value OUT NUMBER) IS BEGIN p_value := NULL; END;
	PROCEDURE out_date(p_value OUT DATE) IS BEGIN p_value := DATE '2018-01-15'; END;
	PROCEDURE out_timestamp(p_value OUT TIMESTAMP) IS BEGIN p_value := TIMESTAMP '2018-01-15 10:30:45'; END;
	PROCEDURE out_binary_double(p_value OUT BINARY_DOUBLE) IS BEGIN p_value := 1.5d; END;
	PROCEDURE out_boolean(p_value OUT BOOLEAN) IS BEGIN p_value := TRUE; END;
	PROCEDURE invert_boolean(p_value IN OUT BOOLEAN) IS BEGIN p_value := NOT p_value; END;

	PROCEDURE invert_sn(p_value IN OUT VARCHAR2) IS
	BEGIN
		p_value := CASE p_value WHEN 'S' THEN 'N' WHEN 'N' THEN 'S' ELSE 'X' END;
	END;

	PROCEDURE out_invalid_number(p_value OUT VARCHAR2) IS BEGIN p_value := 'abc'; END;

	PROCEDURE out_object(p_value OUT ADDRESS_T) IS BEGIN p_value := ADDRESS_T('Street A', 1); END;

	PROCEDURE change_object(p_value IN OUT ADDRESS_T) IS
	BEGIN
		p_value.num := p_value.num + 1;
	END;

	PROCEDURE out_record(p_value OUT entry) IS
	BEGIN
		p_value.id := 1;
		p_value.description := 'one';
	END;

	PROCEDURE change_record(p_value IN OUT entry) IS
	BEGIN
		p_value.id := p_value.id + 1;
	END;

	PROCEDURE out_rowtype(p_value OUT FIXTURE_ITEM%ROWTYPE) IS
	BEGIN
		p_value.id := 1;
		p_value.description := 'one';
		p_value.quantity := 1.5;
		p_value.registered_at := DATE '2018-01-15';
	END;

	PROCEDURE out_names(p_value OUT NAME_LIST_T) IS BEGIN p_value := NAME_LIST_T('a', NULL, 'b'); END;

	PROCEDURE change_names(p_value IN OUT NAME_LIST_T) IS
	BEGIN
		p_value.EXTEND;
		p_value(p_value.COUNT) := 'new';
	END;

	PROCEDURE out_addresses(p_value OUT ADDRESS_LIST_T) IS
	BEGIN
		p_value := ADDRESS_LIST_T(ADDRESS_T('Street A', 1), ADDRESS_T('Street B', 2));
	END;

	PROCEDURE out_names_pkg(p_value OUT pkg_name_list) IS BEGIN p_value := pkg_name_list('a'); END;

	PROCEDURE out_cursor(p_value OUT SYS_REFCURSOR) IS
	BEGIN
		OPEN p_value FOR SELECT 'Street A' STREET, 1 NUM FROM DUAL UNION ALL SELECT 'Street B', 2 FROM DUAL;
	END;

	PROCEDURE assign_and_fail(p_value IN OUT NUMBER) IS
	BEGIN
		p_value := 99;
		RAISE_APPLICATION_ERROR(-20200, 'failed after assigning');
	END;

	PROCEDURE overloaded(p_value NUMBER) IS BEGIN NULL; END;
	PROCEDURE overloaded(p_value OUT VARCHAR2) IS BEGIN p_value := 'version OUT'; END;

END FIXTURE_OUT;
/
