package de.raindancer.modules.xaeromap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the module does not rebuild what RainsCore already has — the same scan every other module has,
 * see {@code MODULE-LAYOUT.md}, "What belongs in RainsCore instead" — plus the two rules that are this
 * module's own.
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/xaeromap");

    private record Source(String name, String body) {
    }

    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<Source> module() {
        try (Stream<Path> files = Files.walk(ROOT)) {
            List<Source> found = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                found.add(new Source(ROOT.relativize(file).toString(), code(Files.readString(file))));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }

    private static Map<String, String> wheelsAlreadyRound() {
        Map<String, String> forbidden = new LinkedHashMap<>();

        forbidden.put("getScheduler()", "de.raindancer.core.platform.util.Scheduling");
        forbidden.put("runTaskLater", "Scheduling.entityLater / globalLater");
        forbidden.put("runTaskTimer", "Scheduling.asyncTimer / globalTimer / regionTimer");
        forbidden.put("BukkitRunnable", "Scheduling, which handles Folia");

        forbidden.put("saveDefaultConfig", "context.settings(XaeroMapSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load");
        forbidden.put("getConfig()", "the XaeroMapSettings snapshot");

        forbidden.put("getLogger()", "context.log(), which is this module's channel in the shared file");
        forbidden.put("System.out.print", "context.log()");

        forbidden.put("messages.load(", "Messages.defineFrom — load() throws away Core's own wording");

        forbidden.put("new YamlConfiguration()", "de.raindancer.core.data.store.YamlStore");

        // This module's own: java.desktop is a whole platform module dragged into a headless server
        // for six lines of colour arithmetic, and ClaimColourRule has those six lines.
        forbidden.put("java.awt", "ClaimColourRule.rgb — no java.desktop on a headless server");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(8);
        assertThat(module()).anyMatch(source -> source.name().endsWith("XaeroMapModule.java"));
    }

    @Test
    @DisplayName("nothing here is a second copy of something Core already owns")
    void nothingIsReinvented() {
        List<String> reinvented = new ArrayList<>();
        for (Source source : module()) {
            for (Map.Entry<String, String> wheel : wheelsAlreadyRound().entrySet()) {
                if (source.body().contains(wheel.getKey())) {
                    reinvented.add(source.name() + " uses '" + wheel.getKey()
                            + "' — use " + wheel.getValue());
                }
            }
        }
        assertThat(reinvented).isEmpty();
    }

    @Test
    @DisplayName("the module goes through Core's Scheduling for everything it schedules")
    void theModuleGoesThroughScheduling() {
        assertThat(module().stream().filter(source -> source.body().contains("Scheduling.")).toList())
                .as("nothing here schedules or touches a player off-thread without Core's "
                        + "Scheduling, which is what keeps it Folia-safe")
                .isNotEmpty();
    }

    @Test
    @DisplayName("packets go out through Wire, never through a player directly")
    void everythingGoesThroughTheWire() {
        List<String> direct = new ArrayList<>();
        for (Source source : module()) {
            if (source.name().endsWith("Wire.java")) {
                continue;
            }
            if (source.body().contains(".sendPluginMessage(")) {
                direct.add(source.name());
            }
        }
        assertThat(direct)
                .as("Wire is what puts the send on the player's own region thread — and what makes "
                        + "the packets readable by a test, which for this protocol is the only way "
                        + "any of it can be checked at all")
                .isEmpty();
    }

    @Test
    @DisplayName("this module never claims land, only draws it")
    void themapIsReadOnly() {
        // claims-module's own mutating surface, by name. Reading a claim is what this module does;
        // creating, resizing, renaming or deleting one is not, and never becomes so by accident.
        List<String> changing = List.of("claimService()", ".create(", ".resize(", ".rename(",
                ".delete(", ".transferTo(", "ClaimAttempt");
        List<String> writing = new ArrayList<>();
        for (Source source : module()) {
            for (String call : changing) {
                if (source.body().contains(call)) {
                    writing.add(source.name() + " calls '" + call + "'");
                }
            }
        }
        assertThat(writing)
                .as("the mod's own claim key sends a request this server must not answer — doing so "
                        + "would be a second way to claim land, one that knows nothing about who "
                        + "may claim, what it costs or how large a claim may be")
                .isEmpty();
    }
}
