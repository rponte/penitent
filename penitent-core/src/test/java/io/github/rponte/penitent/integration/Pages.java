package io.github.rponte.penitent.integration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads the fenced blocks of the documentation's pages, and the regions of the tests they come
 * from.
 *
 * <p>On a page, a block is named by an HTML comment right before it, which neither GitHub nor the
 * site shows: {@code <!-- snippet: routines/default-values -->}. In a test, a region is the lines
 * between {@code // snippet: routines/default-values} and {@code // end-snippet}.
 */
final class Pages {

	/** The name of a block that is shown without a test, such as configuration or a comparison. */
	static final String UNTESTED = "untested";

	/** The name of an SQL block, whose lines come from the fixtures. */
	static final String FIXTURE = "fixture";

	static final Path DOCS = Path.of("..", "docs");

	/** The tests whose regions the pages show: this module's, and those of penitent-test, for its page. */
	static final List<Path> TESTS = List.of(Path.of("src", "test", "java"),
			Path.of("..", "penitent-test", "src", "test", "java"));

	static final Path FIXTURES = Path.of("src", "test", "resources", "db", "fixtures");

	private static final Pattern MARKER = Pattern.compile("^\\s*<!-- snippet: (\\S+) -->\\s*$");

	private static final Pattern FENCE = Pattern.compile("^(\\s*)```(\\w*)\\s*$");

	private static final Pattern REGION_START = Pattern.compile("^\\s*// snippet: (\\S+)\\s*$");

	private static final Pattern REGION_END = Pattern.compile("^\\s*// end-snippet\\s*$");

	private Pages() {
	}

	/** A fenced block of a page; {@code name} is null when no marker precedes it. */
	record Block(String page, int line, String language, String name, String text) {

		String where() {
			return "docs/" + page + ":" + line;
		}
	}

	static List<Block> blocks() {
		try (Stream<Path> pages = Files.list(DOCS)) {
			List<Block> blocks = new ArrayList<>();
			for (Path page : pages.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
				blocks.addAll(blocksOf(page));
			}
			return blocks;
		} catch (IOException e) {
			throw new UncheckedIOException("the tests run from penitent-core, next to ../docs", e);
		}
	}

	private static List<Block> blocksOf(Path page) throws IOException {
		List<String> lines = Files.readAllLines(page, StandardCharsets.UTF_8);
		List<Block> blocks = new ArrayList<>();
		String name = null;
		for (int i = 0; i < lines.size(); i++) {
			Matcher marker = MARKER.matcher(lines.get(i));
			if (marker.matches()) {
				name = marker.group(1);
				continue;
			}
			Matcher fence = FENCE.matcher(lines.get(i));
			if (fence.matches()) {
				String indent = fence.group(1);
				int start = i;
				List<String> body = new ArrayList<>();
				for (i++; i < lines.size() && !lines.get(i).equals(indent + "```"); i++) {
					body.add(lines.get(i).startsWith(indent) ? lines.get(i).substring(indent.length()) : lines.get(i));
				}
				blocks.add(new Block(page.getFileName().toString(), start + 1, fence.group(2), name, String.join("\n", body)));
				name = null;
			} else if (!lines.get(i).isBlank()) {
				// a marker names only the block right after it
				name = null;
			}
		}
		return blocks;
	}

	/** The block named {@code ref}, written as {@code page#name}, as the page shows it. */
	static String block(String ref) {
		int hash = ref.indexOf('#');
		String page = ref.substring(0, hash);
		String name = ref.substring(hash + 1);
		return blocks().stream()
				.filter(b -> b.page().equals(page) && name.equals(b.name()))
				.map(Block::text)
				.reduce((a, b) -> {
					throw new IllegalStateException("two blocks named " + ref);
				})
				.orElseThrow(() -> new IllegalStateException("docs/" + page + " has no block named " + name));
	}

	/** Every region of the tests, by name, without their common indentation. */
	static Map<String, String> regions() {
		try {
			Map<String, String> regions = new LinkedHashMap<>();
			for (Path file : testFiles()) {
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++) {
					Matcher start = REGION_START.matcher(lines.get(i));
					if (start.matches()) {
						List<String> body = new ArrayList<>();
						for (i++; i < lines.size() && !REGION_END.matcher(lines.get(i)).matches(); i++) {
							body.add(lines.get(i));
						}
						if (regions.put(start.group(1), dedent(body)) != null) {
							throw new IllegalStateException("two regions named " + start.group(1));
						}
					}
				}
			}
			return regions;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The source of every test, to find the page references it makes. */
	static String testSources() {
		try {
			StringBuilder sources = new StringBuilder();
			for (Path file : testFiles()) {
				sources.append(Files.readString(file, StandardCharsets.UTF_8));
			}
			return sources.toString();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<Path> testFiles() throws IOException {
		List<Path> files = new ArrayList<>();
		for (Path root : TESTS) {
			try (Stream<Path> walk = Files.walk(root)) {
				walk.filter(p -> p.toString().endsWith(".java")).sorted().forEach(files::add);
			}
		}
		return files;
	}

	/** Every line of the fixtures, as {@link #sqlLine} compares them. */
	static List<String> fixtureLines() {
		try (Stream<Path> files = Files.list(FIXTURES)) {
			List<String> lines = new ArrayList<>();
			for (Path file : files.filter(p -> p.toString().endsWith(".sql")).sorted().toList()) {
				Files.readAllLines(file, StandardCharsets.UTF_8).stream().map(Pages::sqlLine).forEach(lines::add);
			}
			return lines;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * An SQL line without its comment, with single spaces and in lower case: the pages write PL/SQL in
	 * lower case, and the fixtures in upper case, aligned with tabs.
	 */
	static String sqlLine(String line) {
		int comment = line.indexOf("--");
		String code = comment < 0 ? line : line.substring(0, comment);
		return code.replaceAll("\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT);
	}

	/** Tabs as four spaces, without the indentation the lines have in common, and without trailing spaces. */
	static String dedent(List<String> lines) {
		List<String> expanded = lines.stream().map(Pages::expandTabs).map(String::stripTrailing).toList();
		int common = expanded.stream().filter(l -> !l.isEmpty())
				.mapToInt(l -> l.length() - l.stripLeading().length()).min().orElse(0);
		return String.join("\n", expanded.stream().map(l -> l.isEmpty() ? l : l.substring(common)).toList());
	}

	private static String expandTabs(String line) {
		int i = 0;
		StringBuilder indent = new StringBuilder();
		while (i < line.length() && (line.charAt(i) == '\t' || line.charAt(i) == ' ')) {
			indent.append(line.charAt(i) == '\t' ? "    " : " ");
			i++;
		}
		return indent + line.substring(i);
	}
}
