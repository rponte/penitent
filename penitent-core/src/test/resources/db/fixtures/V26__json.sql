--
-- Oracle's JSON type, from 21c on: IN, OUT, IN OUT and return values, a cursor column, a large document, a scalar at
-- the top, overloads with VARCHAR2, and the places Oracle or the driver refuses it: a collection of JSON, a RECORD
-- with a JSON field and a %ROWTYPE of a table with a JSON column. On an older database these objects are not created,
-- and the tests that use them skip themselves; the version is checked rather than an error caught, so that a mistake
-- in the DDL still fails here.
--
BEGIN
	IF DBMS_DB_VERSION.VERSION >= 21 THEN
		EXECUTE IMMEDIATE 'CREATE TABLE FIXTURE_JSON_ROWS (ID NUMBER, DOC JSON)';
		EXECUTE IMMEDIATE q'[INSERT INTO FIXTURE_JSON_ROWS VALUES (1, JSON('{"name":"one"}'))]';
		EXECUTE IMMEDIATE 'INSERT INTO FIXTURE_JSON_ROWS VALUES (2, NULL)';
		EXECUTE IMMEDIATE q'[
			CREATE PACKAGE FIXTURE_JSON AS
				TYPE docs_t IS TABLE OF JSON;
				TYPE doc_rec IS RECORD (id NUMBER, doc JSON);

				FUNCTION echo(p_doc JSON) RETURN JSON;
				FUNCTION length_of(p_doc JSON) RETURN NUMBER;
				FUNCTION null_doc RETURN JSON;
				PROCEDURE out_doc(p_doc OUT JSON);
				PROCEDURE wrap(p_doc IN OUT JSON);
				FUNCTION big(p_size NUMBER) RETURN JSON;
				FUNCTION scalar RETURN JSON;
				FUNCTION rows RETURN SYS_REFCURSOR;
				FUNCTION kind(p_value JSON) RETURN VARCHAR2;
				FUNCTION kind(p_value VARCHAR2) RETURN VARCHAR2;
				FUNCTION docs RETURN docs_t;
				FUNCTION record_of RETURN doc_rec;
				FUNCTION row_of RETURN FIXTURE_JSON_ROWS%ROWTYPE;
			END FIXTURE_JSON;]';
		EXECUTE IMMEDIATE q'[
			CREATE PACKAGE BODY FIXTURE_JSON AS
				FUNCTION echo(p_doc JSON) RETURN JSON IS BEGIN RETURN p_doc; END;
				FUNCTION length_of(p_doc JSON) RETURN NUMBER IS
				BEGIN
					RETURN DBMS_LOB.GETLENGTH(JSON_SERIALIZE(p_doc RETURNING CLOB));
				END;
				FUNCTION null_doc RETURN JSON IS BEGIN RETURN NULL; END;
				PROCEDURE out_doc(p_doc OUT JSON) IS BEGIN p_doc := JSON('{"out":true}'); END;
				PROCEDURE wrap(p_doc IN OUT JSON) IS
				BEGIN
					p_doc := JSON('{"wrapped":' || JSON_SERIALIZE(p_doc) || '}');
				END;
				FUNCTION big(p_size NUMBER) RETURN JSON IS
					l_text CLOB := '{"text":"';
				BEGIN
					FOR i IN 1 .. p_size LOOP
						DBMS_LOB.WRITEAPPEND(l_text, 1, 'x');
					END LOOP;
					DBMS_LOB.WRITEAPPEND(l_text, 2, '"}');
					RETURN JSON(l_text);
				END;
				FUNCTION scalar RETURN JSON IS BEGIN RETURN JSON('42'); END;
				FUNCTION rows RETURN SYS_REFCURSOR IS
					l_rows SYS_REFCURSOR;
				BEGIN
					OPEN l_rows FOR SELECT id, doc FROM FIXTURE_JSON_ROWS ORDER BY id;
					RETURN l_rows;
				END;
				FUNCTION kind(p_value JSON) RETURN VARCHAR2 IS BEGIN RETURN 'JSON'; END;
				FUNCTION kind(p_value VARCHAR2) RETURN VARCHAR2 IS BEGIN RETURN 'VARCHAR2'; END;
				FUNCTION docs RETURN docs_t IS BEGIN RETURN docs_t(JSON('{"a":1}')); END;
				FUNCTION record_of RETURN doc_rec IS
					l_rec doc_rec;
				BEGIN
					l_rec.id := 1;
					l_rec.doc := JSON('{"a":1}');
					RETURN l_rec;
				END;
				FUNCTION row_of RETURN FIXTURE_JSON_ROWS%ROWTYPE IS
					l_row FIXTURE_JSON_ROWS%ROWTYPE;
				BEGIN
					SELECT * INTO l_row FROM FIXTURE_JSON_ROWS WHERE id = 1;
					RETURN l_row;
				END;
			END FIXTURE_JSON;]';
	END IF;
END;
/
