package io.github.rponte.penitent.internal.driver;

import java.sql.CallableStatement;
import java.sql.SQLException;

/**
 * How a value of a type that the driver does not know crosses it: the call converts it in the database, and the
 * driver sees only a type it knows. Each place of the call where the type matters asks the carrier: the placeholder,
 * the return value, the bind, the null and the output. A type the driver knows has no carrier, and goes the usual way.
 */
public interface Carrier {

	/** The placeholder of a parameter, such as {@code JSON(?)}. */
	String placeholder();

	/** The call around a return value, such as {@code JSON_SERIALIZE(call RETURNING CLOB)}. */
	String result(String call);

	void bind(CallableStatement statement, int position, Object value) throws SQLException;

	void bindNull(CallableStatement statement, int position) throws SQLException;

	void registerOut(CallableStatement statement, int position) throws SQLException;
}
