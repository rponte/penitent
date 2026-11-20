package io.github.rponte.penitent.internal.driver;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

import io.github.rponte.penitent.internal.metadata.RoutineMetadata.Argument;

/** The carriers of a call, from the profile of its connection: one for each type its driver does not know. */
public final class Carriers {

	private final DriverProfile profile;

	private Carriers(DriverProfile profile) {
		this.profile = profile;
	}

	public static Carriers of(DriverProfile profile) {
		return new Carriers(profile);
	}

	/** The carrier of the argument, or empty when the driver knows its type. */
	public Optional<Carrier> of(Argument argument) {
		if (argument.isJson() && !profile.knowsJson()) {
			return Optional.of(JsonAsText.INSTANCE);
		}
		return Optional.empty();
	}

	/**
	 * Oracle's JSON type as its text, for a driver before 21: the database parses the text with {@code JSON(?)}, and
	 * makes a document text again with {@code JSON_SERIALIZE}, as a {@code CLOB}, so of any size. An {@code OUT} or an
	 * {@code IN OUT} takes only a bind of the JSON type itself: it is refused before the call, and never gets here.
	 */
	private enum JsonAsText implements Carrier {
		INSTANCE;

		@Override
		public String placeholder() {
			return "JSON(?)";
		}

		@Override
		public String result(String call) {
			return "JSON_SERIALIZE(" + call + " RETURNING CLOB)";
		}

		@Override
		public void bind(CallableStatement statement, int position, Object value) throws SQLException {
			statement.setObject(position, value);
		}

		@Override
		public void bindNull(CallableStatement statement, int position) throws SQLException {
			statement.setNull(position, Types.VARCHAR);
		}

		@Override
		public void registerOut(CallableStatement statement, int position) throws SQLException {
			statement.registerOutParameter(position, Types.CLOB);
		}
	}
}
