--
-- The example of docs/compatibility.md: a BOOLEAN handed back as VARCHAR2, which keeps the null on driver 23
-- against a database before 23ai. DocsCompatibilityTest runs it on every driver and database.
--

CREATE OR REPLACE PACKAGE CUSTOMER_STATUS_PKG AS

	FUNCTION is_active(p_id NUMBER) RETURN BOOLEAN;
	FUNCTION is_active_yn(p_id NUMBER) RETURN VARCHAR2;

END CUSTOMER_STATUS_PKG;
/

CREATE OR REPLACE PACKAGE BODY CUSTOMER_STATUS_PKG AS

	-- unknown for any other customer
	FUNCTION is_active(p_id NUMBER) RETURN BOOLEAN IS
	BEGIN
		RETURN CASE p_id WHEN 1 THEN TRUE WHEN 2 THEN FALSE END;
	END;

	FUNCTION is_active_yn(p_id NUMBER) RETURN VARCHAR2 IS
	  l_active BOOLEAN := is_active(p_id);
	BEGIN
	  RETURN CASE WHEN l_active THEN 'Y' WHEN NOT l_active THEN 'N' END;
	END;

END CUSTOMER_STATUS_PKG;
/
