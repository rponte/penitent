package io.github.rponte.penitent.internal.binding;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The LOBs a call creates to send a {@code CLOB} or {@code BLOB}, freed when the call ends.
 *
 * <p>A {@code String} above 32 KB does not fit in a {@code VARCHAR2} bind, and a {@code byte[]} is
 * not a {@code BLOB}, so the value goes in a temporary LOB of the call's connection. With a pool,
 * the session outlives the call, and a LOB that is not freed would stay in the session until it
 * ends.
 *
 * <p>Per thread, because a call runs entirely in the thread that invoked the proxy.
 */
public final class TemporaryLobs {

	private static final ThreadLocal<List<Object>> CREATED = new ThreadLocal<>();

	private TemporaryLobs() {
	}

	/** Starts collecting the LOBs of a call; a nested call shares the list of the outer one. */
	public static boolean open() {
		if (CREATED.get() != null) {
			return false;
		}
		CREATED.set(new ArrayList<>());
		return true;
	}

	/** Frees what the call created. A failure to free does not hide the result of the call. */
	public static void close(boolean opened) {
		if (!opened) {
			return;
		}
		List<Object> created = CREATED.get();
		CREATED.remove();
		for (Object lob : created) {
			try {
				if (lob instanceof Clob clob) {
					clob.free();
				} else if (lob instanceof Blob blob) {
					blob.free();
				}
			} catch (SQLException e) {
				// the session frees it when it ends
			}
		}
	}

	static Clob clob(Connection connection, String text) throws SQLException {
		Clob clob = connection.createClob();
		register(clob);
		clob.setString(1, text);
		return clob;
	}

	static Blob blob(Connection connection, byte[] bytes) throws SQLException {
		Blob blob = connection.createBlob();
		register(blob);
		blob.setBytes(1, bytes);
		return blob;
	}

	/**
	 * The value a {@code CLOB} or {@code BLOB} receives: a {@code String}, a {@code byte[]} or a
	 * {@code Clob}/{@code Blob} that did not come from the driver go in a temporary LOB.
	 */
	public static Object forLob(String oracleType, Object value, Connection connection) throws SQLException {
		if (oracleType.endsWith("CLOB")) {
			if (value instanceof CharSequence text) {
				return clob(connection, text.toString());
			}
			if (value instanceof Clob clob && !isFromDriver(clob)) {
				return clob(connection, clob.getSubString(1, Math.toIntExact(clob.length())));
			}
		}
		if (oracleType.equals("BLOB")) {
			if (value instanceof byte[] bytes) {
				return blob(connection, bytes);
			}
			if (value instanceof Blob blob && !isFromDriver(blob)) {
				return blob(connection, blob.getBytes(1, Math.toIntExact(blob.length())));
			}
		}
		return value;
	}

	private static boolean isFromDriver(Object lob) {
		return lob.getClass().getName().startsWith("oracle.");
	}

	private static void register(Object lob) {
		List<Object> created = CREATED.get();
		if (created != null) {
			created.add(lob);
		}
	}
}
