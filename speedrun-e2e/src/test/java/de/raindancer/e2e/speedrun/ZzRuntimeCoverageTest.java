package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Coverage;
import de.raindancer.e2e.E2e;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Last in an e2e run (the name sorts it there): what the scenarios claimed, proven by what actually
 * happened — the probe's record of every server the run used, and what each scenario recorded after
 * its own assertions passed.
 *
 * <ul>
 *   <li>every command and word was typed by a player;</li>
 *   <li>every page was opened, and on every page every button the page offered was clicked;</li>
 *   <li>every setting was changed by a player in game;</li>
 *   <li>everything else — refusals, chat buttons, states, behaviours — was recorded by its scenario
 *       after that scenario saw it work.</li>
 * </ul>
 */
@Tag("e2e")
class ZzRuntimeCoverageTest {

    @Test
    @DisplayName("everything the catalogue names was really played in this run — every button of every page included")
    void everythingWasPlayed() throws Exception {
        List<Coverage.Event> events = Coverage.events();
        List<String> commands = Coverage.playerCommands(events);
        Set<String> menus = Coverage.menusOpened(events);
        Set<String> done = Coverage.done();
        List<String> unproven = new ArrayList<>();
        Set<String> proven = new LinkedHashSet<>();

        for (Catalog.Entry entry : Catalog.all()) {
            String id = entry.id();
            if (Catalog.NOT_PLAYABLE.containsKey(id)) {
                continue;
            }
            boolean played = switch (entry.category()) {
                case "words" -> {
                    String[] parts = id.split(":");
                    String root = parts[1];
                    String word = parts[2];
                    yield commands.stream().anyMatch(line -> line.equals(root + " " + word)
                            || line.startsWith(root + " " + word + " "));
                }
                case "commands" -> {
                    String name = id.substring("command:".length());
                    yield commands.stream().anyMatch(line -> line.equals(name) || line.startsWith(name + " "));
                }
                case "menus" -> menus.contains(id.substring("menu:".length()));
                case "settings" -> {
                    String key = id.substring("setting:".length());
                    yield commands.stream().anyMatch(line -> line.startsWith("settings set " + key + " "));
                }
                default -> done.contains(id);
            };
            if (played) {
                proven.add(id);
            } else {
                unproven.add(id);
            }
        }

        Map<String, Set<String>> unclicked = Coverage.buttonsNeverClicked(events,
                holder -> holder.startsWith("de.raindancer.modules.speedrun"));

        List<String> report = new ArrayList<>();
        report.add("proven in game: " + proven.size() + "/" + (Catalog.all().size() - Catalog.NOT_PLAYABLE.size()));
        report.add("pages opened: " + menus.size() + ", buttons never clicked: " + unclicked);
        report.add("unproven: " + unproven);
        Files.write(E2e.out().resolve("runtime-coverage.txt"), report);

        assertThat(unproven).as("claimed by a scenario, but never seen happening in this run").isEmpty();
        assertThat(unclicked).as("buttons a page offered that no bot ever clicked").isEmpty();
    }
}
