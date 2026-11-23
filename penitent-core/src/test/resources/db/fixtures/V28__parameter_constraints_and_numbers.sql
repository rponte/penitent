--
-- Two kinds of rule a value meets on its way to a routine (#141). A constraint the routine declares, on a formal
-- parameter or on its return value, which Oracle checks when it assigns the value, before the routine's first line or
-- on its way out: NOT NULL and a range are inherited, a size and a precision are not, as Oracle documents in
-- "Formal Parameters of Constrained Subtypes". And the limits of the NUMBER type itself, which no value beyond can
-- cross, whatever the routine declares. Each function returns what arrived, as text.
--
CREATE TABLE FIXTURE_LIMITS_ROWS (CODE VARCHAR2(3))
/
CREATE TYPE NUMBER_ATTR_T AS OBJECT (N NUMBER, B BINARY_DOUBLE);
/
CREATE TYPE NUMBER_LIST_T AS TABLE OF NUMBER;
/
CREATE PACKAGE FIXTURE_LIMITS AS
	SUBTYPE range_t IS PLS_INTEGER RANGE 1 .. 10;
	SUBTYPE code_t IS VARCHAR2(3);
	SUBTYPE small_t IS NUMBER(3);
	FUNCTION positive_p(p_value POSITIVE) RETURN VARCHAR2;
	FUNCTION natural_p(p_value NATURAL) RETURN VARCHAR2;
	FUNCTION positiven_p(p_value POSITIVEN) RETURN VARCHAR2;
	FUNCTION simple_p(p_value SIMPLE_INTEGER) RETURN VARCHAR2;
	FUNCTION pls_p(p_value PLS_INTEGER) RETURN VARCHAR2;
	FUNCTION range_p(p_value range_t) RETURN VARCHAR2;
	FUNCTION code_p(p_value code_t) RETURN VARCHAR2;
	FUNCTION small_p(p_value small_t) RETURN VARCHAR2;
	FUNCTION coltype_p(p_value FIXTURE_LIMITS_ROWS.code%TYPE) RETURN VARCHAR2;
	PROCEDURE store_code(p_value FIXTURE_LIMITS_ROWS.code%TYPE);
	FUNCTION ret_positive RETURN POSITIVE;
	FUNCTION ret_code RETURN code_t;
	FUNCTION num_text(p_value NUMBER) RETURN VARCHAR2;
	FUNCTION bd_text(p_value BINARY_DOUBLE) RETURN VARCHAR2;
	FUNCTION attr_text(p_value NUMBER_ATTR_T) RETURN VARCHAR2;
	FUNCTION list_text(p_value NUMBER_LIST_T) RETURN VARCHAR2;
END FIXTURE_LIMITS;
/
CREATE PACKAGE BODY FIXTURE_LIMITS AS
	FUNCTION positive_p(p_value POSITIVE) RETURN VARCHAR2 IS BEGIN RETURN TO_CHAR(p_value); END;
	FUNCTION natural_p(p_value NATURAL) RETURN VARCHAR2 IS BEGIN RETURN TO_CHAR(p_value); END;
	FUNCTION positiven_p(p_value POSITIVEN) RETURN VARCHAR2 IS BEGIN RETURN TO_CHAR(p_value); END;
	FUNCTION simple_p(p_value SIMPLE_INTEGER) RETURN VARCHAR2 IS BEGIN RETURN TO_CHAR(p_value); END;
	FUNCTION pls_p(p_value PLS_INTEGER) RETURN VARCHAR2 IS BEGIN RETURN TO_CHAR(p_value); END;
	FUNCTION range_p(p_value range_t) RETURN VARCHAR2 IS BEGIN RETURN TO_CHAR(p_value); END;
	FUNCTION code_p(p_value code_t) RETURN VARCHAR2 IS BEGIN RETURN p_value; END;
	FUNCTION small_p(p_value small_t) RETURN VARCHAR2 IS BEGIN RETURN TO_CHAR(p_value); END;
	FUNCTION coltype_p(p_value FIXTURE_LIMITS_ROWS.code%TYPE) RETURN VARCHAR2 IS BEGIN RETURN p_value; END;
	PROCEDURE store_code(p_value FIXTURE_LIMITS_ROWS.code%TYPE) IS
	BEGIN
		INSERT INTO FIXTURE_LIMITS_ROWS (code) VALUES (p_value);
	END;
	FUNCTION ret_positive RETURN POSITIVE IS BEGIN RETURN -1; END;
	FUNCTION ret_code RETURN code_t IS BEGIN RETURN 'abcdef'; END;
	FUNCTION num_text(p_value NUMBER) RETURN VARCHAR2 IS BEGIN RETURN TO_CHAR(p_value, 'TM9'); END;
	FUNCTION bd_text(p_value BINARY_DOUBLE) RETURN VARCHAR2 IS BEGIN RETURN TO_CHAR(p_value); END;
	FUNCTION attr_text(p_value NUMBER_ATTR_T) RETURN VARCHAR2 IS
	BEGIN
		RETURN NVL(TO_CHAR(p_value.n, 'TM9'), 'null') || ' ' || NVL(TO_CHAR(p_value.b), 'null');
	END;
	FUNCTION list_text(p_value NUMBER_LIST_T) RETURN VARCHAR2 IS
		l_text VARCHAR2(4000);
	BEGIN
		FOR i IN 1 .. p_value.COUNT LOOP
			l_text := l_text || CASE WHEN i > 1 THEN ', ' END || TO_CHAR(p_value(i), 'TM9');
		END LOOP;
		RETURN l_text;
	END;
END FIXTURE_LIMITS;
/
