package de.raindancer.modules.rtp;

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
 * That the module does not rebuild what RainsCore already has. The same scan warps and the farm
 * worlds have, plus the one specific to picking a random point.
 *
 * @see <a href="file:../../MODULE-LAYOUT.md">MODULE-LAYOUT.md</a> — "What belongs in RainsCore instead"
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/rtp");

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

        forbidden.put("saveDefaultConfig", "context.settings(RtpSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load, through ReloadService");
        forbidden.put("getConfig()", "the RtpSettings snapshot");

        forbidden.put("implements InventoryHolder", "de.raindancer.core.ui.menu.Menu");
        forbidden.put("Bukkit.createInventory", "Menu, which owns the window");

        forbidden.put("getLogger()", "context.log(), which is this module's channel in the shared file");
        forbidden.put("System.out.print", "context.log()");

        forbidden.put("messages.load(", "Messages.defineFrom — load() throws away Core's own wording");

        // The teleport itself. The warm-up, the movement cancelling, finding somewhere safe to land
        // and the teleport are all de.raindancer.core.world.teleport.Travel's.
        forbidden.put("teleportAsync", "de.raindancer.core.world.teleport.Travel");

        // The cooldown.
        forbidden.put("new ConcurrentHashMap", "de.raindancer.core.platform.util.Cooldowns");
        forbidden.put("lastUsed", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");

        // Walking off the block.
        forbidden.put("PlayerMoveEvent", "Core's TravelListener, which already cancels a warm-up when "
                + "somebody walks off the block");

        // Picking a random point. This is what the farm worlds already have, moved to Core once a
        // second module wanted it — a hand-rolled `Random` and trigonometry here would be exactly the
        // wheel that move was meant to stop rolling a third time.
        forbidden.put("nextDouble() * 2 * Math", "de.raindancer.core.world.teleport.Scatter");
        forbidden.put("Math.sqrt(inner", "de.raindancer.core.world.teleport.Scatter");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(8);
        assertThat(module()).anyMatch(source -> source.name().endsWith("RtpModule.java"));
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
    @DisplayName("the module goes through Core for the travelling and the picking it does")
    void theModuleGoesThroughCore() {
        List<Source> travelling = module().stream()
                .filter(source -> source.body().contains("Travel") && source.body().contains("Trip"))
                .toList();
        assertThat(travelling)
                .as("nothing here builds a journey out of Core's Travel and Trip, which is what a "
                        + "rewrite that quietly dropped the warm-up would look like")
                .isNotEmpty();

        List<Source> scattering = module().stream()
                .filter(source -> source.body().contains("Scatter"))
                .toList();
        assertThat(scattering)
                .as("nothing here picks a random point through Core's Scatter, which is the "
                        + "arithmetic that keeps arrivals from clustering at the middle")
                .isNotEmpty();

        List<Source> checking = module().stream()
                .filter(source -> source.body().contains("Safety"))
                .toList();
        assertThat(checking)
                .as("nothing here checks a landing through Core's Safety, which is what finds solid "
                        + "ground rather than dropping somebody wherever the random point turned out "
                        + "to be")
                .isNotEmpty();
    }

    @Test
    @DisplayName("who decides whether a landing is checked is Core's own policy enum, not a second one")
    void theSafetyPolicyIsCores() {
        List<Source> usingFlagPolicy = module().stream()
                .filter(source -> source.body().contains("FlagPolicy"))
                .toList();
        assertThat(usingFlagPolicy)
                .as("AVAILABLE / FORCED_ON / FORCED_OFF / DISABLED is exactly the shape this needed, "
                        + "and Core already has it for the claim flags — a hand-rolled enum here would "
                        + "be the same four answers under a different name")
                .isNotEmpty();
    }
}
