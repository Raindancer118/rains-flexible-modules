package de.raindancer.modules.voicebridge;

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
 * That the module does not rebuild what RainsCore already has — see {@code MODULE-LAYOUT.md},
 * "What belongs in RainsCore instead".
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/voicebridge");

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
        forbidden.put("new Thread(", "Scheduling.async — JDA's own threads are JDA's business");
        forbidden.put("saveDefaultConfig", "context.settings(VoiceBridgeSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load");
        forbidden.put("getConfig()", "the VoiceBridgeSettings snapshot");
        forbidden.put("implements InventoryHolder", "de.raindancer.core.ui.menu.Menu");
        forbidden.put("Bukkit.createInventory", "Menu, which owns the window");
        forbidden.put("getLogger()", "context.log(), which is this module's channel in the shared file");
        forbidden.put("System.out.print", "context.log()");
        forbidden.put("messages.load(", "Messages.defineFrom — load() throws away Core's own wording");
        forbidden.put("new YamlConfiguration()", "de.raindancer.core.data.store.YamlStore");
        forbidden.put("sendActionBar(", "de.raindancer.core.ui.actionbar.ActionBars");
        forbidden.put("playSound(", "Core's Effects, asked by meaning (Cues.NO …)");
        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(8);
        assertThat(module()).anyMatch(source -> source.name().endsWith("VoiceBridgeModule.java"));
    }

    @Test
    @DisplayName("nothing here is a second copy of something Core already owns")
    void nothingIsReinvented() {
        List<String> reinvented = new ArrayList<>();
        for (Source source : module()) {
            for (Map.Entry<String, String> wheel : wheelsAlreadyRound().entrySet()) {
                if (source.body().contains(wheel.getKey())) {
                    reinvented.add(source.name() + " uses '" + wheel.getKey() + "' — use " + wheel.getValue());
                }
            }
        }
        assertThat(reinvented).isEmpty();
    }

    @Test
    @DisplayName("the module goes through Core's Scheduling for everything it schedules")
    void theModuleGoesThroughScheduling() {
        assertThat(module()).anyMatch(source -> source.body().contains("Scheduling."));
    }

    @Test
    @DisplayName("the module's menus are Core's Menu, not a hand-rolled InventoryHolder")
    void theModuleUsesCoresMenu() {
        assertThat(module()).anyMatch(source -> source.body().contains("extends Menu"));
    }

    @Test
    @DisplayName("what a player is told comes out of messages.yml, not out of a string in the code")
    void theWordingIsNotInTheCode() {
        assertThat(module()).anyMatch(source -> source.body().contains("messages().send("));
    }
}
