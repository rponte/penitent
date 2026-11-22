--
-- Oracle's range of dates, from January 1, 4712 BC to December 31, 9999, and every way a date goes in: a parameter,
-- IN and IN OUT, an attribute of an OBJECT, an element of a collection and a block's bind. Each routine returns what
-- arrived, as text, so that a test sees whether the value at an edge arrived exactly, and whether a value beyond it
-- arrived at all (#142).
--
CREATE TYPE DATE_ATTR_T AS OBJECT (D DATE);
/
CREATE TYPE DATE_LIST_T AS TABLE OF DATE;
/
CREATE PACKAGE FIXTURE_DATES AS
	FUNCTION date_text(p_value DATE) RETURN VARCHAR2;
	FUNCTION timestamp_text(p_value TIMESTAMP) RETURN VARCHAR2;
	FUNCTION tstz_text(p_value TIMESTAMP WITH TIME ZONE) RETURN VARCHAR2;
	FUNCTION echo_date(p_value DATE) RETURN DATE;
	PROCEDURE keep(p_value IN OUT DATE);
	FUNCTION attr_text(p_value DATE_ATTR_T) RETURN VARCHAR2;
	FUNCTION list_text(p_value DATE_LIST_T) RETURN VARCHAR2;
END FIXTURE_DATES;
/
CREATE PACKAGE BODY FIXTURE_DATES AS
	FUNCTION date_text(p_value DATE) RETURN VARCHAR2 IS
	BEGIN
		RETURN TO_CHAR(p_value, 'SYYYY-MM-DD HH24:MI:SS');
	END;
	FUNCTION timestamp_text(p_value TIMESTAMP) RETURN VARCHAR2 IS
	BEGIN
		RETURN TO_CHAR(p_value, 'SYYYY-MM-DD HH24:MI:SS.FF6');
	END;
	FUNCTION tstz_text(p_value TIMESTAMP WITH TIME ZONE) RETURN VARCHAR2 IS
	BEGIN
		RETURN TO_CHAR(p_value, 'SYYYY-MM-DD HH24:MI:SS TZH:TZM');
	END;
	FUNCTION echo_date(p_value DATE) RETURN DATE IS BEGIN RETURN p_value; END;
	PROCEDURE keep(p_value IN OUT DATE) IS BEGIN NULL; END;
	FUNCTION attr_text(p_value DATE_ATTR_T) RETURN VARCHAR2 IS
	BEGIN
		RETURN date_text(p_value.d);
	END;
	FUNCTION list_text(p_value DATE_LIST_T) RETURN VARCHAR2 IS
		l_text VARCHAR2(4000);
	BEGIN
		FOR i IN 1 .. p_value.COUNT LOOP
			l_text := l_text || CASE WHEN i > 1 THEN ', ' END || date_text(p_value(i));
		END LOOP;
		RETURN l_text;
	END;
END FIXTURE_DATES;
/
