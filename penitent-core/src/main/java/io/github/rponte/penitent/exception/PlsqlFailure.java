package io.github.rponte.penitent.exception;

import java.sql.SQLException;

/**
 * A database failure, with the point of the call where it happened.
 *
 * @param sql the statement that failed; null when the failure happened before there was one
 * @param cause the failure, with Oracle's code and message
 * @param phase the point of the call where it happened
 */
public record PlsqlFailure(String sql, SQLException cause, Phase phase) {

	public enum Phase {
		/** While obtaining or releasing the connection. */
		CONNECTION,
		/** While reading the dictionary, to discover the routine's signature or its types. */
		DICTIONARY,
		/** While preparing the call, before it reaches the database. */
		PREPARATION,
		/** During the call, with the routine running. */
		EXECUTION,
		/**
		 * While reading the return value and the {@code OUT} parameters, after the call. A cursor
		 * only runs its query here, so an error in that query still belongs to the routine.
		 */
		RESULT
	}

	/**
	 * The codes that say nothing about the routine's logic, as inclusive ranges. The ranges come from
	 * the {@code SQLSTATE} classes in the Oracle documentation (60000 to 64000, 66000, 67000, 69000
	 * and 90000), plus some individual codes.
	 */
	private static final int[][] NOT_FROM_ROUTINE = {
			// the routine never ran, or the call did not compile
			{ 4060, 4069 }, { 6508, 6508 }, { 6550, 6550 },
			// system errors
			{ 370, 429 }, { 600, 899 }, { 6430, 6449 }, { 7200, 7999 }, { 9700, 9999 },
			// server and background processes
			{ 100, 120 }, { 440, 569 },
			// resources and concurrency
			{ 18, 35 }, { 50, 68 }, { 2376, 2399 }, { 4020, 4039 }, { 8177, 8177 }, { 30006, 30006 },
			// connection, session and driver
			{ 1012, 1013 }, { 1017, 1017 }, { 2700, 2899 }, { 3100, 3199 }, { 6000, 6149 }, { 6250, 6429 },
			{ 6600, 6999 }, { 12100, 12299 }, { 12500, 12599 }, { 17000, 17999 },
			// XA, two-task and SQL*Connect
			{ 150, 159 }, { 570, 599 }, { 6200, 6249 }, { 7000, 7199 },
			// debug events
			{ 10000, 10999 },
			// files, redo and licensing
			{ 200, 369 }, { 430, 439 }, { 1100, 1250 },
			// PL/SQL internal failure
			{ 6500, 6501 },
	};

	/**
	 * Whether the failure came from the routine's execution: a {@code RAISE_APPLICATION_ERROR}, but
	 * also a {@code NO_DATA_FOUND} or a violated constraint. Left out is whatever happened at another
	 * point of the call and, even with the routine running, any infrastructure failure or failure of
	 * the call itself.
	 *
	 * <p>The error stack is not part of the criterion, and an {@code ORA-06550} is always treated as
	 * a failure of the call, even when it comes from dynamic SQL inside the routine.
	 */
	public boolean raisedByRoutine() {
		if (phase != Phase.EXECUTION && phase != Phase.RESULT) {
			return false;
		}
		int code = cause.getErrorCode();
		for (int[] range : NOT_FROM_ROUTINE) {
			if (code >= range[0] && code <= range[1]) {
				return false;
			}
		}
		return true;
	}
}
