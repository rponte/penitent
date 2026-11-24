--
-- A document built in the database with dates, which Oracle's JSON type stores as dates of its own, in OSON: a Map
-- reads each one as a String, the text Oracle writes for it, since the document crosses as text. From 21c on, as the
-- JSON type; on an older database the package is not created, and the test skips itself.
--
BEGIN
	IF DBMS_DB_VERSION.VERSION >= 21 THEN
		EXECUTE IMMEDIATE q'[
			CREATE PACKAGE FIXTURE_JSON_DATES AS
				FUNCTION stored RETURN JSON;
			END FIXTURE_JSON_DATES;]';
		EXECUTE IMMEDIATE q'[
			CREATE PACKAGE BODY FIXTURE_JSON_DATES AS
				FUNCTION stored RETURN JSON IS
					l_doc JSON;
				BEGIN
					SELECT JSON_OBJECT('day' VALUE DATE '2026-11-24',
							'at' VALUE TIMESTAMP '2026-11-24 10:15:30.123456' RETURNING JSON)
						INTO l_doc FROM DUAL;
					RETURN l_doc;
				END;
			END FIXTURE_JSON_DATES;]';
	END IF;
END;
/
