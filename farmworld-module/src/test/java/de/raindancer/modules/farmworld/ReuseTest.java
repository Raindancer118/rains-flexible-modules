package de.raindancer.modules.farmworld;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the module does not rebuild what RainsCore already has.
 *
 * <h2>Why this is a test rather than a note in a readme</h2>
 * Because this is the mistake the whole arrangement exists to prevent, and it is never made deliberately. It is
 * made at half past eleven by somebody who needs a delayed task, does not know {@code Scheduling} exists, and
 * writes {@code getScheduler().runTaskLater(...)} — which works on Paper, is a crash on Folia, and is found by
 * exactly one server.
 *
 * <p>This module is the youngest in the repository and was written with all six others already in it, so the
 * entries below are not fossils of a port. They are the wheels those modules already rolled, plus the ones
 * specific to a feature whose whole job is done by somebody else: <b>the farm worlds themselves are Core's.</b>
 * A second store of them, a second regeneration timer or a second copy of the deletion guard would each be a
 * disagreement about which folders get deleted, which is the one disagreement in this repository that cannot be
 * recovered from.
 *
 * @see <a href="file:../../MODULE-LAYOUT.md">MODULE-LAYOUT.md</a> — "What belongs in RainsCore instead"
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/farmworld");

    private record Source(String name, String body) {
    }

    /**
     * The file with its comments taken out.
     *
     * <p>Every rule below is about what the code <em>does</em>, and this module documents itself at length —
     * {@code FarmWorldCatalogue} explains why there is no store of its own, and {@code NoticeService} explains
     * why it must never regenerate anything. Scanned raw, both of those sentences read as the very thing they
     * were written to forbid, and the only way to keep the test passing would be to delete the explanation.
     */
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

    /** What the module must not grow its own version of, and what it should use instead. */
    private static Map<String, String> wheelsAlreadyRound() {
        Map<String, String> forbidden = new LinkedHashMap<>();

        // Scheduling. A teleport belongs to the destination's region, a warm-up countdown to the traveller's,
        // and the warning timer to the global one — on Folia none of those is the thread a command runs on, and
        // a hand-rolled BukkitRunnable is right on Paper and a crash there.
        forbidden.put("getScheduler()", "de.raindancer.core.platform.util.Scheduling");
        forbidden.put("runTaskLater", "Scheduling.entityLater / globalLater");
        forbidden.put("runTaskTimer", "Scheduling.globalTimer / asyncTimer");
        forbidden.put("BukkitRunnable", "Scheduling, which handles Folia");

        // The settings. The record *is* the schema, so a hand-rolled Options record and a saveDefaultConfig are
        // a second, worse config system.
        forbidden.put("saveDefaultConfig", "context.settings(FarmWorldSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load");
        forbidden.put("getConfig()", "the FarmWorldSettings snapshot");

        // The write-to-a-temporary-then-move dance — still Core's, via YamlStore, even though the file it
        // writes (farmworlds.yml) and the state that owns it (FarmWorldState) are this module's own now.
        forbidden.put("StandardCopyOption.ATOMIC_MOVE", "de.raindancer.core.data.store.YamlStore");
        forbidden.put("Files.createTempFile", "YamlStore, which owns the write-and-move");

        // The menu framework and the confirmation. The version this replaced had one per plugin, which is why
        // the same server looked like five plugins — and the dialog had been written out three times.
        forbidden.put("implements InventoryHolder", "de.raindancer.core.ui.menu.Menu");
        forbidden.put("Bukkit.createInventory", "Menu, which owns the window");
        // Deliberately not "extends ConfirmMenu": the module's own ConfirmScreen does exactly that, on purpose,
        // so that ScreenGrammarTest can go on proving every danger button is guarded. What must not appear is a
        // page that builds the dialog itself — three rows with a yes and a no, which is what had been written
        // out three times before ConfirmMenu existed.
        forbidden.put("super(viewer, brand, parent, 3)", "de.raindancer.core.ui.menu.ConfirmMenu");

        // Logging. A module logs to its own channel in the shared file, not to a plugin's logger.
        forbidden.put("getLogger()", "context.log(), which is this module's channel in the shared file");
        forbidden.put("System.out.print", "context.log()");

        // Wording. Messages.defineFrom is the one way a module's bundled messages.yml becomes a floor under the
        // owner's; a module that flattened the YAML itself would be a second copy of that.
        forbidden.put("messages.load(", "Messages.defineFrom — load() throws away Core's own wording");

        // The teleport itself. The warm-up, the movement cancelling, finding somewhere safe to land and the
        // teleport are all Travel's — the same code the warps, the homes and the teleport requests use.
        forbidden.put("teleportAsync", "de.raindancer.core.world.teleport.Travel");
        forbidden.put("PlayerMoveEvent", "Core's TravelListener, which already cancels a warm-up when somebody "
                + "walks off the block");

        // The wait between trips. A ConcurrentHashMap of "when did this player last go" is exactly what
        // Cooldowns already is, and every hand-rolled copy could let two clicks in the same millisecond through.
        forbidden.put("lastUsed", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");
        forbidden.put("lastTrip", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");

        // Lengths of time. Written a hundred lines at a time in every plugin that took one, each understanding a
        // slightly different three units — and the m/M distinction a schedule cannot afford to get wrong.
        forbidden.put("endsWith(\"d\")", "de.raindancer.core.world.time.Times.parse");
        forbidden.put("TimeUnit.DAYS.toMillis", "Times, which reads and writes what people actually type");

        // Sounds and bars. Asked for by meaning so a server that rebinds one rebinds every plugin's; and a
        // player has three bar slots at most, so who wins is arbitration nobody can do alone.
        forbidden.put("playSound(", "de.raindancer.core.ui.effect.Effects, with a cue from Cues");
        forbidden.put("BossBar.bossBar(", "de.raindancer.core.ui.bossbar.BossBars");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(15);
        assertThat(module()).anyMatch(source -> source.name().endsWith("FarmWorldModule.java"));
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
        assertThat(reinvented)
                .as("every one of these is a second answer to a question the server should have one answer to")
                .isEmpty();
    }

    @Test
    @DisplayName("the module goes through Core for the travelling it does")
    void theModuleGoesThroughCoreForTravelling() {
        // The inverse of the scan above: it is not enough that a hand-rolled teleport is absent, the real one
        // has to be there, or a rewrite that simply dropped the warm-up and the safe-landing search would pass.
        List<Source> travelling = module().stream()
                .filter(source -> source.body().contains("Travel") && source.body().contains("Trip"))
                .toList();

        assertThat(travelling)
                .as("nothing here builds a journey out of Core's Travel and Trip, which is what a rewrite that "
                        + "quietly dropped the warm-up would look like")
                .isNotEmpty();
    }

    /**
     * Files that legitimately touch the farm-world mechanism directly — the doors themselves, not
     * something reaching past them. Kept as one list so the three tests below agree on what counts
     * as a door, rather than three copies of the same exemption drifting apart.
     */
    private static final Set<String> FARM_WORLD_MECHANISM_FILES = Set.of(
            "FarmWorldCatalogue.java",   // the door everything else in this module reads/changes through
            "FarmWorldModule.java",     // the one place FarmWorlds/FarmWorldState are built and scheduled
            "FarmWorlds.java",          // the mechanism itself
            "FarmWorldState.java"       // the mechanism's own state and deletion guard
    );

    @Test
    @DisplayName("the farm worlds themselves are reached through exactly one door")
    void thereIsOneDoorToTheFarmWorlds() {
        // Everything else that reads or changes a farm world goes through FarmWorldCatalogue, so there is one
        // place where "which farm worlds exist" is answered. The alternative is a screen and a service with two
        // ideas of what is on the list, and the thing on the end of that list is a folder that gets deleted.
        List<String> reachingPastIt = new ArrayList<>();
        for (Source source : module()) {
            if (FARM_WORLD_MECHANISM_FILES.contains(source.name().substring(
                    source.name().lastIndexOf('/') + 1))) {
                continue;
            }
            if (source.body().contains("new FarmWorlds(") || source.body().contains("new FarmWorldState(")
                    || source.body().contains(".state()")) {
                reachingPastIt.add(source.name());
            }
        }
        assertThat(reachingPastIt)
                .as("these reach the farm world mechanism directly instead of going through FarmWorldCatalogue")
                .isEmpty();
    }

    @Test
    @DisplayName("exactly one place decides a farm world is due — not zero, and not two")
    void exactlyOnePlaceDecidesSomethingIsDue() {
        // The single most dangerous thing this module could grow a second copy of. Two places each deciding a
        // farm world is due is two regenerations racing, and the loser deletes a folder the winner has already
        // recreated — see FarmWorldModule's own class note on why there is now exactly one timer, where there
        // used to be this one plus Core's.
        List<String> deciding = new ArrayList<>();
        for (Source source : module()) {
            if (source.name().endsWith("FarmWorlds.java")) {
                continue;   // where regenerateWhatIsDue is declared, not called
            }
            if (source.body().contains("regenerateWhatIsDue")) {
                deciding.add(source.name());
            }
        }
        assertThat(deciding)
                .as("regenerateWhatIsDue must be called from exactly one place — FarmWorldModule's own "
                        + "regen timer — or a second copy risks racing it")
                .containsExactly("FarmWorldModule.java");
    }

    @Test
    @DisplayName("the deletion guard exists in exactly one place, not copied anywhere else")
    void theDeletionGuardIsNotCopiedElsewhere() {
        // FarmWorldState.mayDelete is the one pure function standing between a typed command and a deleted
        // server. A second copy of it anywhere else in this module would be one that could be more permissive
        // than the first — and the more permissive of two answers is the one that runs.
        List<String> copying = new ArrayList<>();
        for (Source source : module()) {
            if (source.name().endsWith("FarmWorldState.java")) {
                continue;   // the guard itself
            }
            if (source.body().contains("Files.walk") || source.body().contains("Files.delete")
                    || source.body().contains("deleteIfExists") || source.body().contains("level.dat")) {
                copying.add(source.name());
            }
        }
        assertThat(copying)
                .as("nothing outside FarmWorldState may delete a file — a second, looser copy of mayDelete "
                        + "is how a farm world command ends up deleting something it should have refused")
                .isEmpty();
    }
}
