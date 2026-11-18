--
-- Composite types whose quoted names hold a dot, which a name split on its dots would cut in two: a table, an object
-- type, a collection, a package RECORD and a collection of rows, and a package whose name holds a dot. A double quote
-- cannot be part of a name: Oracle refuses it, with ORA-25716 on 23ai.
--

CREATE TABLE "Dot.Table" (ID NUMBER, NAME VARCHAR2(20));

CREATE TYPE "Dot.T" AS OBJECT (ID NUMBER, NAME VARCHAR2(20));
/

CREATE TYPE "Dot.List" AS TABLE OF "Dot.T";
/

/

/

CREATE PACKAGE FIXTURE_ODD_NAMES AS
	TYPE "Dot.Rec" IS RECORD (ID NUMBER, NAME VARCHAR2(20));
	TYPE "Dot.Rows" IS TABLE OF "Dot.Table"%ROWTYPE;

	FUNCTION echo_dot_row(p_value "Dot.Table"%ROWTYPE) RETURN "Dot.Table"%ROWTYPE;
	FUNCTION echo_dot_object(p_value "Dot.T") RETURN "Dot.T";
	FUNCTION echo_dot_list(p_value "Dot.List") RETURN "Dot.List";
	FUNCTION echo_dot_record(p_value "Dot.Rec") RETURN "Dot.Rec";
	FUNCTION echo_dot_rows(p_value "Dot.Rows") RETURN "Dot.Rows";
END FIXTURE_ODD_NAMES;
/

CREATE PACKAGE BODY FIXTURE_ODD_NAMES AS
	FUNCTION echo_dot_row(p_value "Dot.Table"%ROWTYPE) RETURN "Dot.Table"%ROWTYPE IS BEGIN RETURN p_value; END;
	FUNCTION echo_dot_object(p_value "Dot.T") RETURN "Dot.T" IS BEGIN RETURN p_value; END;
	FUNCTION echo_dot_list(p_value "Dot.List") RETURN "Dot.List" IS BEGIN RETURN p_value; END;
	FUNCTION echo_dot_record(p_value "Dot.Rec") RETURN "Dot.Rec" IS BEGIN RETURN p_value; END;
	FUNCTION echo_dot_rows(p_value "Dot.Rows") RETURN "Dot.Rows" IS BEGIN RETURN p_value; END;
END FIXTURE_ODD_NAMES;
/

CREATE PACKAGE "Dot.Pkg" AS
	TYPE item_rec IS RECORD (ID NUMBER, NAME VARCHAR2(20));

	FUNCTION echo_record(p_value item_rec) RETURN item_rec;
END "Dot.Pkg";
/

CREATE PACKAGE BODY "Dot.Pkg" AS
	FUNCTION echo_record(p_value item_rec) RETURN item_rec IS BEGIN RETURN p_value; END;
END "Dot.Pkg";
/
