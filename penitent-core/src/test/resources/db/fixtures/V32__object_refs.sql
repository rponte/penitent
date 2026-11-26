-- An object type with a REF to its own type, and a collection of REFs: the object-relational REF, which Penitent does
-- not map yet (#125), and refuses with a clear error rather than treating it as the object it points to.
CREATE TYPE REF_NODE_T AS OBJECT (id NUMBER, parent REF REF_NODE_T);
/

CREATE TYPE REF_NODE_LIST_T AS TABLE OF REF REF_NODE_T;
/

CREATE OR REPLACE PACKAGE FIXTURE_REFS AS
	FUNCTION echo_node(p_value REF_NODE_T) RETURN REF_NODE_T;
	FUNCTION count_refs(p_value REF_NODE_LIST_T) RETURN NUMBER;
END FIXTURE_REFS;
/

CREATE OR REPLACE PACKAGE BODY FIXTURE_REFS AS
	FUNCTION echo_node(p_value REF_NODE_T) RETURN REF_NODE_T IS BEGIN RETURN p_value; END;
	FUNCTION count_refs(p_value REF_NODE_LIST_T) RETURN NUMBER IS BEGIN RETURN p_value.COUNT; END;
END FIXTURE_REFS;
/
