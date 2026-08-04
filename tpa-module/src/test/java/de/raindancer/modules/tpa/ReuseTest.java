package de.raindancer.modules.tpa;

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

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/tpa");

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
        forbidden.put("saveDefaultConfig", "context.settings(TpaSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load, through ReloadService");
        forbidden.put("getConfig()", "the TpaSettings snapshot");

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
        // Deliberately NOT "new ConcurrentHashMap" outright, the way the homes module has it: this
        // module has two maps that are stores rather than cooldowns — who has asked whom, and who has
        // blocked whom — and forbidding the type would forbid the module's own subject matter. The
        // field names are the tell instead, and the "when did they last" shape has a test of its own
        // below scoped to the services.
        forbidden.put("lastUsed", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");
        forbidden.put("lastRequest", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");
        forbidden.put("lastBack", "de.raindancer.core.platform.util.Cooldowns, not a field of that name");

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
        assertThat(module()).anyMatch(source -> source.name().endsWith("TpaModule.java"));
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
    @DisplayName("the block lists an upgrading server already has are still read")
    void theOldPrefsAreStillRead() {
        // Silent if it breaks: the plugin starts, everybody's block list is empty, and the file with
        // them in it is still sitting on disk looking correct. Nobody reports that as a migration bug —
        // they report that somebody they blocked can suddenly ask them again.
        String prefs = module().stream()
                .filter(source -> source.name().endsWith("TpaPrefsFile.java"))
                .map(Source::body)
                .findFirst()
                .orElseThrow(() -> new AssertionError("TpaPrefsFile is gone"));

        assertThat(prefs)
                .as("the file is no longer read under the name the old plugin wrote it as")
                .contains("FILE_NAME = \"tpa.yml\"");
        assertThat(prefs)
                .as("the shape on disk has to stay players.<uuid>.blocked, or every list empties")
                .contains("\"blocked\"")
                .contains("\"accepting\"");

        assertThat(module())
                .as("and nothing loads it, so it would never be read")
                .anyMatch(source -> source.name().endsWith("TpaModule.java")
                        && source.body().contains("prefsFile.load()"));
    }

    @Test
    @DisplayName("no service keeps its own idea of when somebody last did something")
    void theWaitsAreCores() {
        // The two waits in this module — between requests, and between going back — were hand-rolled
        // maps of timestamps in the old plugin, and its version could let two clicks in the same
        // millisecond both through. Scoped to the services, because the stores legitimately hold maps.
        List<String> hoarding = new ArrayList<>();
        for (Source source : module()) {
            if (!source.name().startsWith("service" + java.io.File.separator)) {
                continue;
            }
            if (source.body().contains("new ConcurrentHashMap")
                    || source.body().contains("System.currentTimeMillis() -")) {
                hoarding.add(source.name());
            }
        }
        assertThat(hoarding)
                .as("these work out for themselves how long ago something was, instead of asking "
                        + "Cooldowns — which is where the check-and-record has to be one operation")
                .isEmpty();
    }

    @Test
    @DisplayName("nobody is looked up by name against Mojang")
    void nobodyIsLookedUpByName() {
        // getOfflinePlayer(String) blocks on a request to Mojang, from what on Folia may be a region
        // thread — the whole server waits on somebody else's web service because a player typed a name
        // with a typo in it. Every lookup here is by uuid, or among the people actually online.
        List<String> blocking = new ArrayList<>();
        for (Source source : module()) {
            if (source.body().contains("getOfflinePlayer(String")
                    || source.body().matches("(?s).*getOfflinePlayer\\(\\s*(args|name|typed)\\b.*")) {
                blocking.add(source.name());
            }
        }
        assertThat(blocking)
                .as("these resolve a player by name through Bukkit, which is a blocking web request")
                .isEmpty();
    }
}
