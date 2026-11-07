-- For the log: a function with OUT and IN OUT.
CREATE OR REPLACE PACKAGE FIXTURE_LOG AS
	FUNCTION compute(p_a NUMBER, p_doubled OUT NUMBER, p_accumulated IN OUT NUMBER) RETURN VARCHAR2;
END FIXTURE_LOG;
/

CREATE OR REPLACE PACKAGE BODY FIXTURE_LOG AS
	FUNCTION compute(p_a NUMBER, p_doubled OUT NUMBER, p_accumulated IN OUT NUMBER) RETURN VARCHAR2 IS
	BEGIN
		p_doubled := p_a * 2;
		p_accumulated := p_accumulated + p_a;
		RETURN 'ok';
	END;
END FIXTURE_LOG;
/
