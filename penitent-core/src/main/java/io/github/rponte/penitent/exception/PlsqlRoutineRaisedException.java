package io.github.rponte.penitent.exception;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A routine failed while running, either through {@code RAISE_APPLICATION_ERROR} or with an error
 * such as {@code NO_DATA_FOUND} or a violated constraint. The criterion is in
 * {@link PlsqlFailure#raisedByRoutine()}.
 *
 * <p>{@link #getMessage()} is what Oracle said, with the {@code ORA-} prefix and the stack, because
 * that is what people search for in a log. {@link #getCode()} and {@link #getReason()} are the error
 * number and text.
 */
public class PlsqlRoutineRaisedException extends PlsqlExecutionException {

	private static final long serialVersionUID = 1L;

	/** The first message of the stack, which may span several lines, up to the next ORA or the documentation link. */
	private static final Pattern RAISED_MESSAGE = Pattern
			.compile("^ORA-\\d+:\\s?(.*?)(?=\\nORA-\\d+:|\\nhttps?://|\\s*\\z)", Pattern.DOTALL);

	private final String reason;

	public PlsqlRoutineRaisedException(String sql, SQLException cause) {
		super(sql, cause);
		this.reason = reasonOf(cause);
	}

	/**
	 * Only the error text, without the {@code ORA-} prefix or the stack. It is empty for a {@code RAISE}
	 * of an exception bound to a code with {@code EXCEPTION_INIT}, because nobody supplied a text there.
	 */
	public String getReason() {
		return reason;
	}

	private static String reasonOf(SQLException exception) {
		Matcher matcher = RAISED_MESSAGE.matcher(exception.getMessage());
		return matcher.find() ? matcher.group(1) : exception.getMessage();
	}
}
