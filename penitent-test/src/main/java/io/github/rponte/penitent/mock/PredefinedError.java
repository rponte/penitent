package io.github.rponte.penitent.mock;

/**
 * Oracle's predefined exceptions, which the package {@code STANDARD} declares, with the Oracle code each reaches Java
 * with. Read from {@code STANDARD} on Oracle 23ai. Most reach Java as a
 * {@link io.github.rponte.penitent.exception.PlsqlRoutineRaisedException}, as an error the routine raised; five that
 * stand for a failure of the session or the server, {@link #LOGIN_DENIED}, {@link #NOT_LOGGED_ON},
 * {@link #PROGRAM_ERROR}, {@link #STORAGE_ERROR} and {@link #TIMEOUT_ON_RESOURCE}, reach it as a
 * {@link io.github.rponte.penitent.exception.PlsqlExecutionException}, as they would from the real routine.
 */
public enum PredefinedError implements PlsqlError {

	ACCESS_INTO_NULL(6530),
	CASE_NOT_FOUND(6592),
	COLLECTION_IS_NULL(6531),
	CURSOR_ALREADY_OPEN(6511),
	DUP_VAL_ON_INDEX(1),
	INVALID_CURSOR(1001),
	INVALID_NUMBER(1722),
	LOGIN_DENIED(1017),
	/** {@code SQLCODE} 100, which reaches Java as {@code ORA-01403}. */
	NO_DATA_FOUND(1403),
	NO_DATA_NEEDED(6548),
	NOT_LOGGED_ON(1012),
	PROGRAM_ERROR(6501),
	ROWTYPE_MISMATCH(6504),
	SELF_IS_NULL(30625),
	STORAGE_ERROR(6500),
	SUBSCRIPT_BEYOND_COUNT(6533),
	SUBSCRIPT_OUTSIDE_LIMIT(6532),
	SYS_INVALID_ROWID(1410),
	TIMEOUT_ON_RESOURCE(51),
	TOO_MANY_ROWS(1422),
	USERENV_COMMITSCN_ERROR(1725),
	VALUE_ERROR(6502),
	ZERO_DIVIDE(1476);

	private final int code;

	PredefinedError(int code) {
		this.code = code;
	}

	/** The Oracle error number, as in {@code ORA-01422} for {@link #TOO_MANY_ROWS}. */
	public int code() {
		return code;
	}

	@Override
	public String raiseName() {
		return name();
	}
}
