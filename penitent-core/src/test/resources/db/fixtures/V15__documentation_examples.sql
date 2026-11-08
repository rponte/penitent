--
-- The examples of the documentation, under the names the pages use. DocsGettingStartedTest and
-- DocsRoutinesTest run them, with a copy of what the pages show.
--

CREATE TABLE ORDERS
(
	ID           NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	CUSTOMER_ID  NUMBER NOT NULL,
	TOTAL        NUMBER(10, 2) NOT NULL,
	STATUS       VARCHAR2(10) DEFAULT 'OPEN' NOT NULL
);

CREATE OR REPLACE PACKAGE ORDERS_PKG AS

	FUNCTION calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER;

	PROCEDURE place_order(p_customer_id NUMBER, p_total NUMBER, p_order_id OUT NUMBER);
	PROCEDURE cancel_order(p_order_id NUMBER);
	FUNCTION order_status(p_order_id NUMBER) RETURN VARCHAR2;

	PROCEDURE apply_discount(p_total IN OUT NUMBER, p_percent NUMBER DEFAULT 10);

	FUNCTION count_orders(p_customer_id NUMBER, p_status VARCHAR2 DEFAULT NULL, p_min_total NUMBER DEFAULT NULL)
		RETURN NUMBER;

	FUNCTION describe(p_order_id NUMBER) RETURN VARCHAR2;
	FUNCTION describe(p_status VARCHAR2) RETURN VARCHAR2;

END ORDERS_PKG;
/

CREATE OR REPLACE PACKAGE BODY ORDERS_PKG AS

	FUNCTION calculate_freight(p_origin VARCHAR2, p_destination VARCHAR2) RETURN NUMBER IS
	BEGIN
		RETURN CASE WHEN p_origin = p_destination THEN 10 ELSE 25.5 END;
	END;

	PROCEDURE place_order(p_customer_id NUMBER, p_total NUMBER, p_order_id OUT NUMBER) IS
	BEGIN
		INSERT INTO ORDERS (CUSTOMER_ID, TOTAL) VALUES (p_customer_id, p_total)
		RETURNING ID INTO p_order_id;
	END;

	PROCEDURE cancel_order(p_order_id NUMBER) IS
	BEGIN
		UPDATE ORDERS SET STATUS = 'CANCELLED' WHERE ID = p_order_id AND STATUS = 'OPEN';
		IF SQL%ROWCOUNT = 0 THEN
			RAISE_APPLICATION_ERROR(-20001, 'order ' || p_order_id || ' is not open');
		END IF;
	END;

	FUNCTION order_status(p_order_id NUMBER) RETURN VARCHAR2 IS
		l_status ORDERS.STATUS%TYPE;
	BEGIN
		SELECT STATUS INTO l_status FROM ORDERS WHERE ID = p_order_id;
		RETURN l_status;
	END;

	PROCEDURE apply_discount(p_total IN OUT NUMBER, p_percent NUMBER DEFAULT 10) IS
	BEGIN
		p_total := p_total * (100 - p_percent) / 100;
	END;

	FUNCTION count_orders(p_customer_id NUMBER, p_status VARCHAR2 DEFAULT NULL, p_min_total NUMBER DEFAULT NULL)
		RETURN NUMBER IS
		l_count NUMBER;
	BEGIN
		SELECT COUNT(*) INTO l_count
		  FROM ORDERS
		 WHERE CUSTOMER_ID = p_customer_id
		   AND (p_status IS NULL OR STATUS = p_status)
		   AND (p_min_total IS NULL OR TOTAL >= p_min_total);
		RETURN l_count;
	END;

	FUNCTION describe(p_order_id NUMBER) RETURN VARCHAR2 IS
	BEGIN
		RETURN 'order ' || p_order_id;
	END;

	FUNCTION describe(p_status VARCHAR2) RETURN VARCHAR2 IS
	BEGIN
		RETURN 'orders with status ' || p_status;
	END;

END ORDERS_PKG;
/

CREATE FUNCTION ROUND_TO_CENTS(p_value NUMBER) RETURN NUMBER IS
BEGIN
	RETURN ROUND(p_value, 2);
END;
/
