package de.raindancer.modules.worldgate;

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
 * That the module does not rebuild what RainsCore already has, and does go through its own rule
 * rather than hand-rolling the decision inline in the listener. The same shape of scan warps, the
 * farm worlds and rtp already have.
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/worldgate");

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
        forbidden.put("runTaskTimer", "Scheduling.asyncTimer / globalTimer");
        forbidden.put("BukkitRunnable", "Scheduling, which handles Folia");

        forbidden.put("saveDefaultConfig", "context.settings(WorldGateSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load, through ReloadService");
        forbidden.put("getConfig()", "the WorldGateSettings snapshot");

        forbidden.put("getLogger()", "context.log(), which is this module's channel in the shared file");
        forbidden.put("System.out.print", "context.log()");

        forbidden.put("messages.load(", "Messages.defineFrom — load() throws away Core's own wording");

        // A store hand-rolling YamlConfiguration would lose the write-to-a-temporary-then-move
        // guarantee YamlStore already gives every other store in this project.
        forbidden.put("new YamlConfiguration()", "de.raindancer.core.data.store.YamlStore");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(8);
        assertThat(module()).anyMatch(source -> source.name().endsWith("WorldGateModule.java"));
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
    @DisplayName("the listener's decision goes through GateRule, not an inline comparison")
    void theListenerGoesThroughTheRule() {
        Source listener = module().stream()
                .filter(source -> source.name().endsWith("WorldGatePortalListener.java"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the portal listener is gone"));

        assertThat(listener.body())
                .as("the allow/refuse decision must be askable speculatively through GateRule, the "
                        + "same way a screen would grey a button before anybody has done anything")
                .contains("rule.allowed(");
    }

    @Test
    @DisplayName("the portal listener follows FarmWorldPortalListener's own event shape")
    void thePortalListenerMatchesCoresPattern() {
        Source listener = module().stream()
                .filter(source -> source.name().endsWith("WorldGatePortalListener.java"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the portal listener is gone"));

        assertThat(listener.body()).contains("PlayerPortalEvent");
        assertThat(listener.body())
                .as("HIGH, not MONITOR — a MONITOR handler that cancels the event is the classic way "
                        + "to have no effect at all")
                .contains("EventPriority.HIGH");
        assertThat(listener.body())
                .as("a portal another plugin already cancelled is not ours to un-cancel")
                .contains("ignoreCancelled = true");
    }
}
