package de.raindancer.modules.homes;

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
 * That the module does not rebuild what RainsCore already has.
 *
 * <h2>Why this is a test rather than a note in a readme</h2>
 * Because this is the mistake the whole arrangement exists to prevent, and it is never made
 * deliberately. It is made at half past eleven by somebody who needs a delayed task, does not know
 * {@code Scheduling} exists, and writes {@code getScheduler().runTaskLater(...)} — which works on Paper,
 * is a crash on Folia, and is found by exactly one server.
 *
 * <p>This module is younger than names, claims and moderation, and was written with all three already
 * in the repository — so the entries below are not fossils of a port, they are the wheels those three
 * modules already rolled that this one must not roll a fourth time, plus the ones specific to sending
 * somebody to a place and knowing who that place is for.
 *
 * @see <a href="file:../../MODULE-LAYOUT.md">MODULE-LAYOUT.md</a> — "What belongs in RainsCore instead"
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/homes");

    private record Source(String name, String body) {
    }

    /**
     * The file with its comments taken out.
     *
     * <p>Every rule below is about what the code <em>does</em>, and this module documents itself at
     * length — the class note on {@code HomeLimitRule} explains the permission scan it must never go
     * back to, and {@code HomeCatalogue} explains the migration. Scanned raw, both of those
     * sentences read as the very thing they were written to forbid, and the only way to keep the test
     * passing would be to delete the explanation.
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

        // Scheduling. A teleport belongs to the destination's region, and a warm-up countdown belongs
        // to the traveller's — on Folia those are not the thread a command runs on, and a hand-rolled
        // BukkitRunnable is right on Paper and a crash there.
        forbidden.put("getScheduler()", "de.raindancer.core.platform.util.Scheduling");
        forbidden.put("runTaskLater", "Scheduling.entityLater / globalLater");
        forbidden.put("runTaskTimer", "Scheduling.asyncTimer / globalTimer");
        forbidden.put("BukkitRunnable", "Scheduling, which handles Folia");

        // The settings. The record *is* the schema — the file, its comments, its validation and the
        // /settings screens all come from it — so a hand-rolled Options record and a saveDefaultConfig
        // are a second, worse config system.
        forbidden.put("saveDefaultConfig", "context.settings(HomeSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load, through ReloadService");
        forbidden.put("getConfig()", "the HomeSettings snapshot");

        // The write-to-a-temporary-then-move dance, and the private writer thread the old plugin had
        // to go with it. Both are the place store's now — each hand-rolled copy was a chance to leave
        // half a file where somebody's homes used to be.
        forbidden.put("StandardCopyOption.ATOMIC_MOVE", "the place store, which owns the atomic write");
        forbidden.put("Files.createTempFile", "the place store, which owns the write-and-move");
        forbidden.put("newSingleThreadExecutor", "the place store, which owns its own writer thread");

        // The menu framework. The version this replaced had one per plugin, which is why the same
        // server looked like five plugins.
        forbidden.put("implements InventoryHolder", "de.raindancer.core.ui.menu.Menu");
        forbidden.put("Bukkit.createInventory", "Menu, which owns the window");

        // Logging. A module logs to its own channel in the shared file, not to a plugin's logger.
        forbidden.put("getLogger()", "context.log(), which is this module's channel in the shared file");
        forbidden.put("System.out.print", "context.log()");

        // Wording. Messages.defineFrom is the one way a module's bundled messages.yml becomes a floor
        // under the owner's; a module that flattened the YAML itself would be a second copy of that.
        forbidden.put("messages.load(", "Messages.defineFrom — load() throws away Core's own wording");

        // The teleport itself. The warm-up, the movement cancelling, finding somewhere safe to land
        // and the teleport are all de.raindancer.core.world.teleport.Travel's — and this module is
        // where Travel came from. The old plugin's HomeService turned out to be identical to the
        // teleport requests' copy, down to the helper that decides whether somebody has moved, and
        // the two were fixed separately for years. Teleporting directly here would start that again.
        forbidden.put("teleportAsync", "de.raindancer.core.world.teleport.Travel");

        // The cooldown. A ConcurrentHashMap of "when did this player last go home" is exactly what
        // de.raindancer.core.platform.util.Cooldowns already is — and the old plugin's own version of
        // it could let two clicks in the same millisecond both through. Forbidding the map's usual
        // field names rather than the type: a UUID-keyed Map of any shape is the same mistake with a
        // different import.
        forbidden.put("new ConcurrentHashMap", "de.raindancer.core.platform.util.Cooldowns");
        forbidden.put("lastUsed", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");
        forbidden.put("lastArrival", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");

        // Walking off the block. Core's TravelListener already watches PlayerMoveEvent and cancels a
        // warm-up when somebody walks away — a module that listened for it too would be a second
        // cancellation racing the first one, on two different threads on Folia.
        forbidden.put("PlayerMoveEvent", "Core's TravelListener, which already cancels a warm-up when "
                + "somebody walks off the block");

        // The place itself. A home is a point of interest RainsCore already keeps — persistence,
        // atomic writes, worlds that are not loaded and "is this reachable" are solved and tested
        // there. Constructing one directly, rather than through the builder the store hands out, is
        // how a home ends up saved without its kind and invisible to everything that looks for one.
        forbidden.put("new Poi(", "Poi.builder(...), through HomeCatalogue");

        // Reading the granted permissions is deliberately NOT in this map. One class is allowed to do
        // it — HomeLimitRule.grantsOf — and a map entry cannot say "everywhere except there", so it
        // has a test of its own below with that exclusion written into it.

        // The item namespace. A key built from the plugin namespaces its data under whichever jar
        // wrote it, and this code ships two ways — standalone, and hosted inside something else.
        forbidden.put("new NamespacedKey(plugin", "a namespace that does not depend on the host");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(15);
        assertThat(module()).anyMatch(source -> source.name().endsWith("HomeModule.java"));
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
                .as("every one of these is a second answer to a question the server should have one "
                        + "answer to")
                .isEmpty();
    }

    @Test
    @DisplayName("the module goes through Core for the travelling it does")
    void theModuleGoesThroughCoreForTravelling() {
        // The inverse of the scan above: it is not enough that a hand-rolled teleport is absent, the
        // real one has to be there, or a rewrite that simply dropped the warm-up and the safe-landing
        // search would pass. Travel and Trip are Core's classes for exactly that, and TravelService
        // — the whole reason the module can send somebody anywhere at all — is built on both.
        List<Source> travelling = module().stream()
                .filter(source -> source.body().contains("Travel") && source.body().contains("Trip"))
                .toList();

        assertThat(travelling)
                .as("nothing here builds a journey out of Core's Travel and Trip, which is what a "
                        + "rewrite that quietly dropped the warm-up would look like")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the module goes through Core to send anybody anywhere")
    void travellingGoesThroughCore() {
        // The inverse of the scan above: it is not enough that the wrong call is absent, the right one
        // has to be there — a rewrite that simply dropped the warm-up would otherwise pass.
        assertThat(module())
                .as("nothing here uses Core's Travel, so either the teleport is gone or it was "
                        + "written again by hand")
                .anyMatch(source -> source.body().contains("Travel") && source.body().contains("Trip"));
    }

    @Test
    @DisplayName("the homes an upgrading server already has are still read")
    void theMigrationIsStillThere() {
        // The worst failure this module has, and a silent one: a home is a place now, so an upgrading
        // server's homes are in the wrong file. If nothing reads the old one, the plugin starts, every
        // list is empty, and the file with everybody's homes in it is still sitting on disk looking
        // perfectly correct. Nobody reports that as a migration bug — they report that homes were
        // deleted.
        String catalogue = module().stream()
                .filter(source -> source.name().endsWith("HomeCatalogue.java"))
                .map(Source::body)
                .findFirst()
                .orElseThrow(() -> new AssertionError("HomeCatalogue is gone"));

        assertThat(catalogue)
                .as("nothing brings the old homes.yml across any more")
                .contains("LegacyHomesFile");

        assertThat(module())
                .as("and nothing calls it, so it would never run")
                .anyMatch(source -> source.name().endsWith("HomeModule.java")
                        && source.body().contains("importLegacy"));
    }

    @Test
    @DisplayName("only the limit rule reads the permissions directly")
    void onlyTheRuleReadsPermissions() {
        // Reading them at all is a decision, and it is one that has been wrong: asking
        // hasPermission("homes.limit." + n) per number gives every operator a hundred homes, because
        // an undeclared node defaults to true for one. Core's NumberedLimit reads what was granted.
        // HomeLimitRule.grantsOf is the single place allowed to do the reading, so a second reader
        // anywhere else is a second answer to "how many may they have".
        List<String> readers = new ArrayList<>();
        for (Source source : module()) {
            if (source.name().endsWith("HomeLimitRule.java")) {
                continue;
            }
            if (source.body().contains("getEffectivePermissions")) {
                readers.add(source.name());
            }
        }
        assertThat(readers)
                .as("these read the granted permissions themselves instead of asking HomeLimitRule")
                .isEmpty();
    }
}
