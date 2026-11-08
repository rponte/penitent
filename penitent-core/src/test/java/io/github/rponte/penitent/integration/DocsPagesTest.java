package io.github.rponte.penitent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.rponte.penitent.integration.Pages.Block;

/**
 * Ties every block of the documentation to where it comes from, without a database. With the
 * {@code Docs*Test} classes, which compare the library's output with the pages, a page cannot drift
 * from the library, nor from its test, without failing the build:
 *
 * <ul>
 * <li>a {@code java} block is a region of a test, which compiles and runs;
 * <li>a {@code text} block is read by a test, which compares it with what the library printed;
 * <li>an {@code sql} block is made of lines of the fixtures;
 * <li>anything else is marked {@code untested}, so the exception shows in review.
 * </ul>
 */
class DocsPagesTest {

	private final List<Block> blocks = Pages.blocks();

	@Test
	@DisplayName("every block of a page says where it comes from")
	void every_block_is_named() {
		List<String> unnamed = blocks.stream().filter(b -> b.name() == null)
				.map(b -> b.where() + " (" + b.language() + ")").toList();

		assertThat(unnamed)
				.as("blocks without <!-- snippet: ... -->: name the test region, the test that reads it, "
						+ "'fixture' for SQL, or '" + Pages.UNTESTED + "'")
				.isEmpty();
	}

	@Test
	@DisplayName("a java block is a region of a test, as it is")
	void java_blocks_are_regions() {
		Map<String, String> regions = Pages.regions();
		List<String> problems = new ArrayList<>();
		for (Block block : named("java")) {
			String region = regions.get(block.name());
			if (region == null) {
				problems.add(block.where() + ": no test has the region " + block.name());
			} else if (!region.equals(Pages.dedent(block.text().lines().toList()))) {
				problems.add(block.where() + ": differs from the region " + block.name() + "\n--- page\n"
						+ block.text() + "\n--- test\n" + region);
			}
		}
		assertThat(problems).isEmpty();
	}

	@Test
	@DisplayName("a text block is compared by a test with what the library printed")
	void text_blocks_are_read_by_a_test() {
		String sources = Pages.testSources();
		List<String> unread = named("text").stream()
				.filter(b -> !sources.contains("\"" + b.page() + "#" + b.name() + "\""))
				.map(b -> b.where() + ": no test reads " + b.page() + "#" + b.name())
				.toList();

		assertThat(unread).isEmpty();
	}

	@Test
	@DisplayName("an sql block is made of lines of the fixtures")
	void sql_blocks_come_from_the_fixtures() {
		Set<String> fixture = new HashSet<>(Pages.fixtureLines());
		List<String> problems = new ArrayList<>();
		for (Block block : blocks) {
			if (!block.language().equals("sql") || Pages.UNTESTED.equals(block.name())) {
				continue;
			}
			if (!Pages.FIXTURE.equals(block.name())) {
				problems.add(block.where() + ": an sql block is named '" + Pages.FIXTURE + "'");
				continue;
			}
			Arrays.stream(block.text().split("\n")).map(Pages::sqlLine)
					.filter(l -> !l.isEmpty() && !l.equals("/") && !fixture.contains(l))
					.forEach(l -> problems.add(block.where() + ": not in the fixtures: " + l));
		}
		assertThat(problems).isEmpty();
	}

	@Test
	@DisplayName("every region of a test is shown on a page")
	void every_region_is_shown() {
		Set<String> shown = new HashSet<>(named("java").stream().map(Block::name).toList());
		List<String> unused = Pages.regions().keySet().stream().filter(r -> !shown.contains(r)).toList();

		assertThat(unused).as("regions that no page shows").isEmpty();
	}

	@Test
	@DisplayName("names are unique on each page")
	void names_are_unique() {
		Set<String> seen = new HashSet<>();
		List<String> repeated = blocks.stream()
				.filter(b -> b.name() != null && !b.name().equals(Pages.UNTESTED) && !b.name().equals(Pages.FIXTURE))
				.filter(b -> !b.language().equals("java"))
				.map(b -> b.page() + "#" + b.name())
				.filter(ref -> !seen.add(ref))
				.toList();

		assertThat(repeated).isEmpty();
	}

	private List<Block> named(String language) {
		return blocks.stream()
				.filter(b -> b.language().equals(language) && b.name() != null && !b.name().equals(Pages.UNTESTED))
				.toList();
	}
}
