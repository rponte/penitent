package io.github.rponte.penitent.mock.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A script in Oracle's dialect, split into its statements by the rules of SQL*Plus and of Flyway's Oracle parser:
 * PL/SQL ends on a line holding only a slash, and keeps its own semicolons; SQL ends with a semicolon, which is
 * removed, or with such a line. Strings, alternative quotes, quoted names and comments never end nor classify a
 * statement. Blocks are not counted, so nothing inside PL/SQL, such as {@code $IF ... $END} or {@code CASE}, can end
 * it early.
 *
 * <p>It knows what a script is, not what the mocks accept: it refuses only what is malformed, such as a string left
 * open or a missing slash. {@link EditionScripts} decides what may be applied.
 */
public final class PlsqlScript {

	/**
	 * A statement of the script: its SQL, as sent to the database; the object it creates, when it creates one; and
	 * where it is, for the messages.
	 *
	 * @param type the object's type, such as {@code VIEW} or {@code PACKAGE BODY}, for a {@code CREATE}; otherwise the
	 *        statement's first word, such as {@code INSERT}
	 * @param owner the object's owner as written, or {@code null} when it is not
	 * @param name the object's name, in upper case unless written in quotes, or {@code null} for no {@code CREATE}
	 */
	public record Statement(String source, int number, int line, String sql, boolean plsql, boolean orReplace,
			String type, String owner, String name) {

		/** Where the statement is: {@code freights.sql, statement 2 (line 7)}. */
		public String where() {
			return source + ", statement " + number + " (line " + line + ")";
		}

		/** The statement's first line, to show it in a message. */
		public String preview() {
			String first = sql.lines().findFirst().orElse("").strip();
			return first.length() > 80 ? first.substring(0, 77) + "..." : first;
		}

		/** {@code CREATE OR REPLACE VIEW FREIGHT_ITEMS_V}, for the log. */
		public String object() {
			return "CREATE" + (orReplace ? " OR REPLACE " : " ") + type + " " + (owner == null ? "" : owner + ".")
					+ name;
		}
	}

	/** Statements that end on a slash line, as SQL*Plus has it: the semicolons inside them are their own. */
	private static final Pattern PLSQL = Pattern.compile("^(?:CREATE(?: OR REPLACE)?(?: (?:NON)?EDITIONABLE)?"
			+ " (?:PACKAGE|FUNCTION|PROCEDURE|TYPE|TRIGGER|LIBRARY|JAVA)\\b|DECLARE\\b|BEGIN\\b"
			+ "|CREATE(?: OR REPLACE)?(?: (?:NO )?FORCE)?(?: (?:NON)?EDITIONABLE)? VIEW\\b.*\\bAS WITH (?:FUNCTION|PROCEDURE)\\b)");

	private static final String NAME = "(\"[^\"]+\"|[A-Za-z][\\w$#]*)";

	private static final Pattern CREATE = Pattern.compile("^CREATE( OR REPLACE)?(?: (?:NO )?FORCE)?(?: (?:NON)?EDITIONABLE)?"
			+ " (PACKAGE BODY|TYPE BODY|[A-Z]+) " + NAME + "(?: ?\\. ?" + NAME + ")?", Pattern.CASE_INSENSITIVE);

	/** Commands of the SQL*Plus client, which a script copied from a tool brings: not SQL, so no driver runs them. */
	private static final Set<String> SQL_PLUS = Set.of("SET", "PROMPT", "PRO", "REM", "REMARK", "SHOW", "SHO", "WHENEVER",
			"SPOOL", "DEFINE", "DEF", "UNDEFINE", "EXIT", "QUIT", "COLUMN", "COL", "EXEC", "EXECUTE", "START", "CONNECT",
			"CONN", "PAUSE", "ACCEPT", "VARIABLE", "VAR", "PRINT", "@", "@@");

	private final String source;
	private final String text;
	private final List<Statement> statements = new ArrayList<>();

	private int position;
	private int line = 1;

	/** Where the current statement starts, and the shape of its text with strings and comments taken out. */
	private int start = -1;
	private int startLine;
	private final StringBuilder shape = new StringBuilder();
	private Boolean plsql;

	private PlsqlScript(String text, String source) {
		this.text = text;
		this.source = source;
	}

	/**
	 * The statements of {@code text}, which comes from {@code source}, named in the messages: a file, or how the text
	 * was given.
	 */
	public static List<Statement> parse(String text, String source) {
		PlsqlScript script = new PlsqlScript(text, source);
		script.read();
		if (script.statements.isEmpty()) {
			throw new IllegalStateException(source + " has no statement: only comments or blank lines");
		}
		return List.copyOf(script.statements);
	}

	private void read() {
		boolean lineStart = true;
		while (position < text.length()) {
			char c = text.charAt(position);
			if (lineStart && isSlashLine()) {
				// ends the statement; after one a semicolon already ended, it is a slash left over
				if (start >= 0) {
					end(position);
				}
				skipLine();
				continue;
			}
			if (lineStart && start >= 0 && startsWord("CREATE")) {
				// no statement has a line starting with CREATE inside it: the one before was not ended
				throw new IllegalStateException(where(startLine) + (looksPlsql()
						? ": the PL/SQL is not ended by a line holding only /"
						: ": the statement is not ended by a ; or a line holding only /")
						+ ", and a new statement starts on line " + line + ": end it before that line");
			}
			if (c == '\n') {
				line++;
				position++;
				lineStart = true;
				shape(' ');
				continue;
			}
			lineStart = false;
			if (c == '-' && next('-')) {
				skipLineComment();
			} else if (c == '/' && next('*')) {
				skipBlockComment();
			} else if (Character.isWhitespace(c)) {
				position++;
				shape(' ');
			} else {
				begin();
				if ((c == 'q' || c == 'Q') && next('\'') && position + 2 < text.length()) {
					skipAlternativeQuote();
				} else if (c == '\'') {
					skipQuoted('\'', "string");
				} else if (c == '"') {
					int from = position;
					skipQuoted('"', "quoted name");
					shape.append(text, from, position);
				} else if (c == ';' && !isPlsql()) {
					end(position);
					position++;
				} else {
					shape.append(Character.toUpperCase(c));
					position++;
				}
			}
		}
		if (start >= 0) {
			end(text.length());
		}
	}

	/** The first character of a statement that is not a comment: the statement starts there. */
	private void begin() {
		if (start >= 0) {
			return;
		}
		start = position;
		startLine = line;
		shape.setLength(0);
		plsql = null;
		String word = firstWord();
		if (SQL_PLUS.contains(word.toUpperCase(Locale.ROOT))) {
			throw new IllegalStateException(where(line) + ": " + word + " is a SQL*Plus command, not SQL, and no driver"
					+ " runs it: take it out of the script");
		}
	}

	/**
	 * Decided at the statement's first semicolon, which ends it when it is SQL: by then its beginning, up to a view's
	 * {@code AS WITH FUNCTION}, has been read.
	 */
	private boolean isPlsql() {
		if (plsql == null) {
			plsql = looksPlsql();
		}
		return plsql;
	}

	/** What the statement read so far looks like, without deciding it. */
	private boolean looksPlsql() {
		return PLSQL.matcher(shape.toString().replaceAll("\\s+", " ").strip()).find();
	}

	private void end(int until) {
		String sql = text.substring(start, until).strip();
		String normalized = shape.toString().replaceAll("\\s+", " ").strip();
		boolean isPlsql = PLSQL.matcher(normalized).find();
		boolean orReplace = false;
		String type;
		String owner = null;
		String name = null;
		Matcher create = CREATE.matcher(normalized);
		if (create.find()) {
			orReplace = create.group(1) != null;
			type = create.group(2).toUpperCase(Locale.ROOT);
			String first = name(create.group(3));
			String second = create.group(4) == null ? null : name(create.group(4));
			owner = second == null ? null : first;
			name = second == null ? first : second;
		} else {
			type = normalized.split(" ", 2)[0];
		}
		statements.add(new Statement(source, statements.size() + 1, startLine, sql, isPlsql, orReplace, type, owner,
				name));
		start = -1;
		plsql = null;
	}

	/** A name as the dictionary has it: in upper case, unless written in quotes. */
	private static String name(String written) {
		return written.startsWith("\"") ? written.substring(1, written.length() - 1) : written.toUpperCase(Locale.ROOT);
	}

	private void shape(char c) {
		if (start >= 0) {
			shape.append(c);
		}
	}

	private boolean next(char c) {
		return position + 1 < text.length() && text.charAt(position + 1) == c;
	}

	/** The line from {@code position} holds only a slash, around blanks. */
	private boolean isSlashLine() {
		int end = text.indexOf('\n', position);
		return text.substring(position, end < 0 ? text.length() : end).strip().equals("/");
	}

	private boolean startsWord(String word) {
		int from = position;
		while (from < text.length() && (text.charAt(from) == ' ' || text.charAt(from) == '\t')) {
			from++;
		}
		return text.regionMatches(true, from, word, 0, word.length())
				&& (from + word.length() == text.length() || !Character.isLetterOrDigit(text.charAt(from + word.length())));
	}

	private String firstWord() {
		int end = position;
		while (end < text.length() && !Character.isWhitespace(text.charAt(end)) && text.charAt(end) != ';') {
			end++;
		}
		String word = text.substring(position, end);
		return word.startsWith("@@") ? "@@" : word.startsWith("@") ? "@" : word;
	}

	private void skipLine() {
		int end = text.indexOf('\n', position);
		position = end < 0 ? text.length() : end;
	}

	private void skipLineComment() {
		skipLine();
	}

	private void skipBlockComment() {
		int opened = line;
		int end = text.indexOf("*/", position + 2);
		if (end < 0) {
			throw new IllegalStateException(where(opened) + ": a comment opened on line " + opened + " is not closed");
		}
		countLines(position, end + 2);
		position = end + 2;
		shape(' ');
	}

	private void skipQuoted(char quote, String what) {
		int opened = line;
		int at = position + 1;
		while (true) {
			int close = text.indexOf(quote, at);
			if (close < 0) {
				throw new IllegalStateException(where(opened) + ": a " + what + " opened on line " + opened
						+ " is not closed");
			}
			if (close + 1 < text.length() && text.charAt(close + 1) == quote) {
				at = close + 2;
				continue;
			}
			countLines(position, close + 1);
			position = close + 1;
			if (quote == '\'') {
				shape.append("''");
			}
			return;
		}
	}

	/** {@code q'[...]'}, whose closing is the opening's pair followed by a quote. */
	private void skipAlternativeQuote() {
		int opened = line;
		char open = text.charAt(position + 2);
		char close = switch (open) {
			case '[' -> ']';
			case '(' -> ')';
			case '{' -> '}';
			case '<' -> '>';
			default -> open;
		};
		int end = text.indexOf(close + "'", position + 3);
		if (end < 0) {
			throw new IllegalStateException(where(opened) + ": a string opened on line " + opened + " is not closed");
		}
		countLines(position, end + 2);
		position = end + 2;
		shape.append("''");
	}

	private void countLines(int from, int to) {
		for (int i = from; i < to; i++) {
			if (text.charAt(i) == '\n') {
				line++;
			}
		}
	}

	private String where(int at) {
		return source + ", statement " + (statements.size() + 1) + " (line " + at + ")";
	}
}
