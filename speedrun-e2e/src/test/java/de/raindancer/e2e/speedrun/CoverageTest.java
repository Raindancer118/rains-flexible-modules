package de.raindancer.e2e.speedrun;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every word, command, page, setting, chat button, state and briefed behaviour of RainsSpeedrun has a
 * scenario that plays it — run in every normal build, so a new one cannot be merged without one.
 * Writes {@code target/e2e/coverage.txt} with the numbers per category.
 */
class CoverageTest {

    /** Every id some scenario says it plays. */
    static Set<String> claimed() throws IOException, URISyntaxException {
        Set<String> claimed = new LinkedHashSet<>();
        Path classes = Path.of(CoverageTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                String name = classes.relativize(file).toString().replace('/', '.').replaceAll("\\.class$", "");
                Class<?> type;
                try {
                    type = Class.forName(name, false, CoverageTest.class.getClassLoader());
                } catch (ClassNotFoundException | LinkageError skipped) {
                    continue;
                }
                for (Method method : type.getDeclaredMethods()) {
                    Covers covers = method.getAnnotation(Covers.class);
                    if (covers != null) {
                        claimed.addAll(List.of(covers.value()));
                    }
                }
            }
        }
        return claimed;
    }

    @Test
    @DisplayName("every entry of the catalogue has a scenario, and no scenario claims what does not exist")
    void everythingIsPlayed() throws Exception {
        List<Catalog.Entry> entries = Catalog.all();
        Set<String> wanted = Catalog.ids(entries);
        Set<String> claimed = claimed();

        List<String> report = new ArrayList<>();
        for (Map.Entry<String, List<String>> category : Catalog.byCategory(entries).entrySet()) {
            long covered = category.getValue().stream().filter(claimed::contains).count();
            long unplayable = category.getValue().stream().filter(Catalog.NOT_PLAYABLE::containsKey).count();
            report.add(category.getKey() + ": " + covered + "/" + (category.getValue().size() - unplayable)
                    + (unplayable == 0 ? "" : " (+" + unplayable + " not playable)"));
        }
        Catalog.NOT_PLAYABLE.forEach((id, why) -> report.add("not playable: " + id + " — " + why));
        Path out = Path.of("target", "e2e");
        Files.createDirectories(out);
        Files.write(out.resolve("coverage.txt"), report);

        List<String> missing = wanted.stream().filter(id -> !claimed.contains(id))
                .filter(id -> !Catalog.NOT_PLAYABLE.containsKey(id)).toList();
        assertThat(Catalog.NOT_PLAYABLE.keySet()).as("not-playable entries that are in the catalogue")
                .allMatch(wanted::contains).noneMatch(claimed::contains);
        List<String> stale = claimed.stream().filter(id -> !wanted.contains(id)).toList();
        assertThat(missing).as("catalogue entries no scenario plays (%s)", report).isEmpty();
        assertThat(stale).as("scenario claims for things that no longer exist").isEmpty();
    }

    @Test
    @DisplayName("the catalogue reads the code: it finds the words, pages and settings it is built from")
    void catalogueIsNotEmpty() {
        Map<String, List<String>> byCategory = Catalog.byCategory(Catalog.all());

        assertThat(byCategory.get("words")).contains("word:speedrun:start", "word:manhunt:give", "word:whitelist:vip");
        assertThat(byCategory.get("refusals")).contains("refused:speedrun:reset", "refused:manhunt:assign",
                "refused:whitelist:clear").doesNotContain("refused:speedrun:menu");
        assertThat(byCategory.get("menus")).contains("menu:SpeedrunLobbyMenu", "menu:ManhuntHubMenu",
                "menu:SpeedrunPreflightMenu");
        assertThat(byCategory.get("settings")).contains("setting:late-join", "setting:late-joiner-side",
                "setting:runner-lives");
        assertThat(byCategory.get("chat buttons")).contains("chat:manhunt.give.usage", "chat:code:[Copy seed]");
    }
}
