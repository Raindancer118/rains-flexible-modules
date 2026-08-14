package de.raindancer.modules.warp;

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

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/warp");

    private record Source(String name, String body) {
    }

    /**
     * The file with its comments taken out.
     *
     * <p>Every rule below is about what the code <em>does</em>, and this module documents itself at
     * length — the class note on {@code WarpCatalogue} explains why every change flushes, and
     * {@code WarpAccess} explains why it is nothing but a permission node. Scanned raw, both of those
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
        forbidden.put("saveDefaultConfig", "context.settings(WarpSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load, through ReloadService");
        forbidden.put("getConfig()", "the WarpSettings snapshot");

        // The write-to-a-temporary-then-move dance. Written seven times inside Core before YamlStore
        // existed; each copy was a chance to leave half a file where somebody's warp used to be.
        forbidden.put("StandardCopyOption.ATOMIC_MOVE", "de.raindancer.core.data.store.YamlStore");
        forbidden.put("Files.createTempFile", "YamlStore, which owns the write-and-move");

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
        // and the teleport are all de.raindancer.core.world.teleport.Travel's — the same code the
        // /teleport command and the homes module use. A module that teleported directly would be a
        // fourth copy of the exact sequence Travel exists to hold in one place.
        forbidden.put("teleportAsync", "de.raindancer.core.world.teleport.Travel");

        // The cooldown. A ConcurrentHashMap of "when did this player last warp" is exactly what
        // de.raindancer.core.platform.util.Cooldowns already is, kept per-warp-system so two of them
        // do not disagree about whether somebody may go again. Forbidding the map's usual field names
        // rather than the type: a UUID-keyed Map of any shape is the same mistake with a different
        // import.
        forbidden.put("new ConcurrentHashMap", "de.raindancer.core.platform.util.Cooldowns");
        forbidden.put("lastUsed", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");
        forbidden.put("lastWarp", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");

        // Walking off the block. Core's TravelListener already watches PlayerMoveEvent and cancels a
        // warm-up when somebody walks away — a module that listened for it too would be a second
        // cancellation racing the first one, on two different threads on Folia.
        forbidden.put("PlayerMoveEvent", "Core's TravelListener, which already cancels a warm-up when "
                + "somebody walks off the block");

        // The store itself. A warp is a point of interest RainsCore already keeps — persistence,
        // atomic writes, worlds that are not loaded and "is this reachable" are solved and tested
        // there, on context.core().places(). What must never happen is a *second* store: this
        // module's own WarpRegistry is the one place that builds a Poi for a warp, and it does so
        // through Poi.builder(...), not the constructor — a second PoiStore instance here would mean
        // a ghast line could not fly to a warp and deleting a world would leave this module's warps
        // pointing at nothing.
        forbidden.put("new Poi(", "Poi.builder(...), which WarpRegistry already uses");
        forbidden.put("new PoiStore(", "context.core().places(), which owns the one store");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(15);
        assertThat(module()).anyMatch(source -> source.name().endsWith("WarpModule.java"));
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
    @DisplayName("the warp registry is built on Core's shared places, not a second store")
    void theRegistryGoesThroughCoresPlaces() {
        // The inverse of the "new PoiStore(" ban above: it is not enough that a second store is
        // absent, WarpRegistry actually has to be handed Core's — this used to be core.warps() and
        // moved out from behind it, so a rewrite that quietly rebuilt a private store would still
        // pass every other check here.
        List<Source> onCoresPlaces = module().stream()
                .filter(source -> source.name().endsWith("WarpRegistry.java"))
                .filter(source -> source.body().contains("PoiStore"))
                .toList();

        assertThat(onCoresPlaces)
                .as("WarpRegistry has to be built on Core's PoiStore type, taken in rather than made")
                .isNotEmpty();

        List<Source> wiredFromCore = module().stream()
                .filter(source -> source.name().endsWith("WarpModule.java"))
                .filter(source -> source.body().contains("context.core().places()"))
                .toList();

        assertThat(wiredFromCore)
                .as("the module has to actually hand WarpRegistry context.core().places() — the "
                        + "shared store, not one built here")
                .isNotEmpty();
    }

    @Test
    @DisplayName("who may use a warp is stored as nothing but a permission, and only once")
    void theAccessModelIsStoredAsNothingButAPermission() {
        // WarpAccess's own javadoc says why: RainsCore already keeps one permission on a place, and a
        // second tag saying "this one is staff" would be two things to keep in step. When they
        // disagreed the winner would decide whether the staff room is open — and there is no way to
        // know in advance which of the two disagreeing values is the winner.
        String warpAccess = module().stream()
                .filter(source -> source.name().endsWith("WarpAccess.java"))
                .map(Source::body)
                .findFirst()
                .orElseThrow(() -> new AssertionError("WarpAccess is gone"));

        assertThat(warpAccess)
                .as("the staff permission has to be declared exactly once, as the node it is")
                .contains("STAFF_PERMISSION = \"rainswarps.warp.staff\"");

        List<String> secondOpinions = new ArrayList<>();
        for (Source source : module()) {
            if (source.body().contains("\"staff-only\"") || source.body().contains("isStaffOnly")) {
                secondOpinions.add(source.name());
            }
        }
        assertThat(secondOpinions)
                .as("a second field or tag for 'is this staff' is a second answer to that question, "
                        + "and the second answer is the one that opens the staff room when it disagrees "
                        + "with the first")
                .isEmpty();
    }
}
