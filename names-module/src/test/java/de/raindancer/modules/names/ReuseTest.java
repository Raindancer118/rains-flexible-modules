package de.raindancer.modules.names;

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
 * <p>This module arrived from a standalone plugin that had to do all of it itself, so every entry below
 * names something that <em>was</em> in that source and is now Core's. Each one is a line that would
 * otherwise creep back in during the next port.
 *
 * @see <a href="file:../../MODULE-LAYOUT.md">MODULE-LAYOUT.md</a> — "What belongs in RainsCore instead"
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/names");

    private record Source(String name, String body) {
    }

    /**
     * The file with its comments taken out.
     *
     * <p>Every rule below is about what the code <em>does</em>, and this module documents itself at
     * length — the class note on {@code Ingredient} explains why it is not an {@code ItemStack}, and
     * {@code StyleTags} explains why its key is not built from the plugin. Scanned raw, both of those
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

        // Scheduling. Both of this module's delayed tasks belong to an entity — the player whose window
        // needs refreshing and the mob being named — and on Folia each belongs to a different thread.
        // The standalone plugin called getScheduler() on the entity directly, which is right on Folia
        // and a second answer to a question Core already answers.
        forbidden.put("getScheduler()", "de.raindancer.core.platform.util.Scheduling");
        forbidden.put("runTaskLater", "Scheduling.entityLater / globalLater");
        forbidden.put("runTaskTimer", "Scheduling.asyncTimer / globalTimer");
        forbidden.put("BukkitRunnable", "Scheduling, which handles Folia");

        // The settings. The record *is* the schema — the file, its comments, its validation and the
        // /settings screens all come from it — so a hand-rolled Options record and a saveDefaultConfig
        // are a second, worse config system.
        forbidden.put("saveDefaultConfig", "context.settings(NamesSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load, through ReloadService");
        forbidden.put("getConfig()", "the NamesSettings snapshot");

        // The write-to-a-temporary-then-move dance. Written seven times inside Core before YamlStore
        // existed; each copy was a chance to leave half a file where somebody's palette used to be.
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
        // under the owner's; a module that flattened the YAML itself would be a second copy of that,
        // and the first version of this port had one.
        forbidden.put("YamlConfiguration.loadConfiguration(new InputStreamReader",
                "Messages.defineFrom(getResourceAsStream(\"messages.yml\"))");
        forbidden.put("messages.load(", "Messages.defineFrom — load() throws away Core's own wording");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(15);
        assertThat(module()).anyMatch(source -> source.name().endsWith("NamesModule.java"));
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
    @DisplayName("the module goes through Core for the two things it schedules")
    void schedulingGoesThroughCore() {
        // The inverse of the scan above: it is not enough that the wrong call is absent, the right one
        // has to be there, or a rewrite that simply dropped the refresh would pass.
        List<String> scheduling = module().stream()
                .filter(source -> source.body().contains("Scheduling."))
                .map(Source::name)
                .toList();

        assertThat(scheduling)
                .as("the crafting window refresh and the mob's name are both scheduled work")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("the persistent-data namespace is the one every already-dyed tag carries")
    void theItemNamespaceIsUnchanged() {
        // A tag dyed by the standalone plugin, or by a server running this as a module of something
        // else, has to stay the same item. Changing this would silently strip every name tag anybody
        // has ever dyed, on every server that upgrades.
        String styleTags = module().stream()
                .filter(source -> source.name().endsWith("StyleTags.java"))
                .map(Source::body)
                .findFirst()
                .orElseThrow(() -> new AssertionError("StyleTags is gone"));

        // The declaration, not a mention: the first version of this test looked for the namespace
        // anywhere in the file and went on passing when the key was changed, because the exception
        // message beside it still named the old one.
        assertThat(styleTags).contains("NAMESPACE = \"colourednames\"");
        assertThat(styleTags)
                .as("a key built from the plugin namespaces the data under whichever jar wrote it, and "
                        + "this code ships two ways")
                .doesNotContain("new NamespacedKey(plugin");
    }
}
