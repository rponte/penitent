--
-- Composite types whose names need quotes, being mixed case: a table, an object type, a package RECORD and a
-- package, each next to an unquoted namesake of another shape, which a name that lost its quotes would reach; and a
-- table, an object type, a package RECORD and collections whose names have a space.
--

CREATE TABLE "Mixed" (ID NUMBER, NAME VARCHAR2(20));

CREATE TABLE MIXED (CODE VARCHAR2(5));

CREATE TABLE "Two Words" (ID NUMBER, NAME VARCHAR2(20));

CREATE TYPE "Two Words_T" AS OBJECT (ID NUMBER, NAME VARCHAR2(20));
/

CREATE TYPE "Two Words_List" AS TABLE OF "Two Words_T";
/

CREATE TYPE "Mixed_T" AS OBJECT (ID NUMBER, NAME VARCHAR2(20));
/

CREATE TYPE MIXED_T AS OBJECT (CODE VARCHAR2(5));
/

CREATE PACKAGE FIXTURE_QUOTED AS
	TYPE "Mixed_Rec" IS RECORD (ID NUMBER, NAME VARCHAR2(20));
	TYPE MIXED_REC IS RECORD (CODE VARCHAR2(5));
	TYPE "Two Words_Rec" IS RECORD (ID NUMBER, NAME VARCHAR2(20));
	TYPE "Two Words_Rows" IS TABLE OF "Two Words"%ROWTYPE;

	FUNCTION echo_rowtype(p_value "Mixed"%ROWTYPE) RETURN "Mixed"%ROWTYPE;

	FUNCTION echo_object(p_value "Mixed_T") RETURN "Mixed_T";

	FUNCTION echo_record(p_value "Mixed_Rec") RETURN "Mixed_Rec";

	FUNCTION echo_two_words_row(p_value "Two Words"%ROWTYPE) RETURN "Two Words"%ROWTYPE;

	FUNCTION echo_two_words_object(p_value "Two Words_T") RETURN "Two Words_T";

	FUNCTION echo_two_words_record(p_value "Two Words_Rec") RETURN "Two Words_Rec";

	FUNCTION echo_two_words_list(p_value "Two Words_List") RETURN "Two Words_List";

	FUNCTION echo_two_words_rows(p_value "Two Words_Rows") RETURN "Two Words_Rows";
END FIXTURE_QUOTED;
/

CREATE PACKAGE BODY FIXTURE_QUOTED AS
	FUNCTION echo_rowtype(p_value "Mixed"%ROWTYPE) RETURN "Mixed"%ROWTYPE IS
	BEGIN
		RETURN p_value;
	END;

	FUNCTION echo_object(p_value "Mixed_T") RETURN "Mixed_T" IS
	BEGIN
		RETURN p_value;
	END;

	FUNCTION echo_record(p_value "Mixed_Rec") RETURN "Mixed_Rec" IS
	BEGIN
		RETURN p_value;
	END;

	FUNCTION echo_two_words_row(p_value "Two Words"%ROWTYPE) RETURN "Two Words"%ROWTYPE IS
	BEGIN
		RETURN p_value;
	END;

	FUNCTION echo_two_words_object(p_value "Two Words_T") RETURN "Two Words_T" IS
	BEGIN
		RETURN p_value;
	END;

	FUNCTION echo_two_words_record(p_value "Two Words_Rec") RETURN "Two Words_Rec" IS
	BEGIN
		RETURN p_value;
	END;

	FUNCTION echo_two_words_list(p_value "Two Words_List") RETURN "Two Words_List" IS
	BEGIN
		RETURN p_value;
	END;

	FUNCTION echo_two_words_rows(p_value "Two Words_Rows") RETURN "Two Words_Rows" IS
	BEGIN
		RETURN p_value;
	END;
END FIXTURE_QUOTED;
/

CREATE PACKAGE "Mixed_Pkg" AS
	TYPE item_rec IS RECORD (ID NUMBER, NAME VARCHAR2(20));

	FUNCTION echo_record(p_value item_rec) RETURN item_rec;
END "Mixed_Pkg";
/

CREATE PACKAGE BODY "Mixed_Pkg" AS
	FUNCTION echo_record(p_value item_rec) RETURN item_rec IS
	BEGIN
		RETURN p_value;
	END;
END "Mixed_Pkg";
/
