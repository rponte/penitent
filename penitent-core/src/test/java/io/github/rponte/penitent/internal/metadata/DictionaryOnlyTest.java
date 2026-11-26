package io.github.rponte.penitent.internal.metadata;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The dictionary's SQL stays in {@link Dictionary} (#176): what it learned about querying it, such as a count
 * correlated to each row or the cost of {@code ALL_SYNONYMS} on 21c, lives in one place. The sources of
 * {@code penitent-core} are read, as the docs tests read the pages: a query of a dictionary view, of {@code dual},
 * or a call of a {@code DBMS_} package, outside {@code Dictionary.java}, fails. Comments that name a view do not.
 */
class DictionaryOnlyTest {

	private static final Pattern DICTIONARY_SQL = Pattern
			.compile("(?i)\\bfrom\\s+(all_\\w+|user_\\w+|dba_\\w+|dual)\\b|\\bdbms_\\w+\\.\\w+\\s*\\(|\\bsys_context\\s*\\(");

	@Test
	@DisplayName("no class but Dictionary writes the dictionary's SQL")
	void only_dictionary() throws IOException {
		Path sources = Path.of("src", "main", "java");
		List<String> found = new ArrayList<>();
		try (Stream<Path> files = Files.walk(sources)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
				if (file.getFileName().toString().equals("Dictionary.java")) {
					continue;
				}
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i).trim();
					boolean comment = line.startsWith("*") || line.startsWith("/*") || line.startsWith("//");
					if (!comment && DICTIONARY_SQL.matcher(line).find()) {
						found.add(sources.relativize(file) + ":" + (i + 1) + ": " + line);
					}
				}
			}
		}
		assertThat(found).as("dictionary SQL outside Dictionary").isEmpty();
	}
}
