--
-- A small schema with one shape of each thing the library has to go through. Nothing
-- here represents business: each object exists to exercise one path of the mapping.
--

CREATE TABLE FIXTURE_ITEM
(
	ID             NUMBER(10) NOT NULL,
	DESCRIPTION    VARCHAR2(60 CHAR),
	QUANTITY       NUMBER(15,2),
	REGISTERED_AT  DATE
);

CREATE TYPE ADDRESS_T AS OBJECT
(
	STREET  VARCHAR2(60),
	NUM     NUMBER(6)
);
/

CREATE TYPE ADDRESS_LIST_T AS TABLE OF ADDRESS_T;
/

CREATE TYPE NAME_LIST_T AS TABLE OF VARCHAR2(60);
/

CREATE TYPE NUMBER_VARRAY_T AS VARRAY(5) OF NUMBER;
/

CREATE TYPE CUSTOMER_T AS OBJECT
(
	ID         NUMBER(10),
	FULL_NAME  VARCHAR2(60),
	ACTIVE     VARCHAR2(1),
	VIP        NUMBER(1),
	BIRTHDAY   DATE,
	STATUS     VARCHAR2(10),
	ADDRESS    ADDRESS_T,
	NICKNAMES  NAME_LIST_T,
	ADDRESSES  ADDRESS_LIST_T
);
/

-- member methods: they can only be invoked on an instance, inside an anonymous block
CREATE TYPE PERIOD_T AS OBJECT
(
	STARTS  DATE,
	ENDS    DATE,
	MEMBER FUNCTION is_valid RETURN BOOLEAN,
	MEMBER FUNCTION conflicts_with(p_other PERIOD_T) RETURN BOOLEAN,
	MEMBER FUNCTION intersection_with(p_other PERIOD_T) RETURN PERIOD_T
);
/

CREATE TYPE BODY PERIOD_T AS

	MEMBER FUNCTION is_valid RETURN BOOLEAN IS
	BEGIN
		RETURN starts <= ends;
	END;

	MEMBER FUNCTION conflicts_with(p_other PERIOD_T) RETURN BOOLEAN IS
	BEGIN
		RETURN starts <= p_other.ends AND p_other.starts <= ends;
	END;

	MEMBER FUNCTION intersection_with(p_other PERIOD_T) RETURN PERIOD_T IS
	BEGIN
		RETURN PERIOD_T(GREATEST(starts, p_other.starts), LEAST(ends, p_other.ends));
	END;

END;
/
