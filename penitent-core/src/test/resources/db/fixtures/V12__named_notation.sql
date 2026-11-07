-- For binding by name: a DEFAULT in the middle, OUT and IN OUT, two parameters of the same type and an
-- overload that differs only by the parameter name.
CREATE OR REPLACE PACKAGE FIXTURE_NAMED AS
	FUNCTION f(p_a NUMBER, p_b NUMBER DEFAULT 7, p_c NUMBER) RETURN NUMBER;
	PROCEDURE p(p_a NUMBER, p_doubled OUT NUMBER, p_accumulated IN OUT NUMBER);
	FUNCTION period(p_start DATE, p_end DATE) RETURN VARCHAR2;
	FUNCTION g(p_x NUMBER) RETURN VARCHAR2;
	FUNCTION g(p_y NUMBER) RETURN VARCHAR2;
	FUNCTION no_arguments RETURN NUMBER;
	FUNCTION concat_names(p_prefix VARCHAR2, p_names NAME_LIST_T, p_suffix VARCHAR2) RETURN VARCHAR2;
END FIXTURE_NAMED;
/

CREATE OR REPLACE PACKAGE BODY FIXTURE_NAMED AS
	FUNCTION f(p_a NUMBER, p_b NUMBER DEFAULT 7, p_c NUMBER) RETURN NUMBER IS
	BEGIN
		RETURN p_a * 100 + p_b * 10 + p_c;
	END;

	PROCEDURE p(p_a NUMBER, p_doubled OUT NUMBER, p_accumulated IN OUT NUMBER) IS
	BEGIN
		p_doubled := p_a * 2;
		p_accumulated := p_accumulated + p_a;
	END;

	FUNCTION period(p_start DATE, p_end DATE) RETURN VARCHAR2 IS
	BEGIN
		RETURN TO_CHAR(p_start, 'YYYY-MM-DD') || '..' || TO_CHAR(p_end, 'YYYY-MM-DD');
	END;

	FUNCTION g(p_x NUMBER) RETURN VARCHAR2 IS BEGIN RETURN 'x'; END;

	FUNCTION g(p_y NUMBER) RETURN VARCHAR2 IS BEGIN RETURN 'y'; END;

	FUNCTION no_arguments RETURN NUMBER IS BEGIN RETURN 42; END;

	FUNCTION concat_names(p_prefix VARCHAR2, p_names NAME_LIST_T, p_suffix VARCHAR2) RETURN VARCHAR2 IS
		v_result VARCHAR2(200) := p_prefix;
	BEGIN
		FOR i IN 1 .. p_names.COUNT LOOP
			v_result := v_result || p_names(i);
		END LOOP;
		RETURN v_result || p_suffix;
	END;
END FIXTURE_NAMED;
/
