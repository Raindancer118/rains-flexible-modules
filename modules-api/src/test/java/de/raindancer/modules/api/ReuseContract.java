package de.raindancer.modules.api;

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
 * That a module does not grow its own version of something RainsCore already owns.
 *
 * <h2>Why this is an interface, like {@link WordingContract}</h2>
 * {@code hungergames-module} has had a {@code ReuseTest} of this shape for a while and it is the only
 * module that had one, which means the rule held in exactly one place. The instruction it exists to
 * keep — <em>use Core's APIs and features wherever possible</em> — is the kind that holds for about a
 * fortnight when nothing checks it. A module opts in by saying where its sources are:
 *
 * <pre>
 * class ReuseTest implements ReuseContract {
 *     public Path moduleSource() { return Path.of("src/main/java/de/raindancer/modules/manhunt"); }
 * }
 * </pre>
 *
 * <h2>What it can and cannot see</h2>
 * It reads text, so it catches the shapes that are wrong wherever they appear and nothing subtler. The
 * rules below are the ones that are unambiguous: a hand-rolled scheduler is wrong on Folia whatever it
 * is for, a second config system is a second config system, and a menu that builds its own window is
 * the reason a server used to look like five plugins.
 *
 * <p>Two rules are scoped to the screens rather than the whole module, because the same text is right
 * in one place and wrong in the other: {@code new ItemStack} is how you make a compass to hand
 * somebody, and is never how you make a button; {@code AmountChooser} versus a ±pair is a question
 * only a screen can get wrong.
 *
 * <p>Comments are stripped before scanning — several of these modules explain at length why they do
 * <em>not</em> do the thing being forbidden, and a scan of the raw text would fail on the explanation.
 */
public interface ReuseContract {

    /** The module's own source root, relative to its project directory. */
    Path moduleSource();

    /** Where this module's screens live, if it has any — used by the screen-only rules. */
    default Path screenSource() {
        return moduleSource().resolve("screen");
    }

    /**
     * Anything this module is allowed to keep despite a rule below, and why.
     *
     * <p>An exemption is a decision, not a way of getting the build green: it names the file, so
     * adding a second one is a deliberate act somebody has to write down.
     */
    default Map<String, String> allowed() {
        return Map.of();
    }

    record Source(String name, String body) {
    }

    /** The file with its comments taken out — see the class javadoc. */
    private static String code(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
    }

    private static List<Source> sourcesUnder(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            List<Source> found = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                found.add(new Source(root.relativize(file).toString(), code(Files.readString(file))));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + root, unreadable);
        }
    }

    /** What no module may rebuild, and what it should reach for instead. */
    private static Map<String, String> wheelsAlreadyRound() {
        Map<String, String> forbidden = new LinkedHashMap<>();

        // Scheduling. A hand-rolled BukkitRunnable is right on Paper and a crash on Folia, and it is
        // found by exactly one server.
        forbidden.put("getScheduler()", "de.raindancer.core.platform.util.Scheduling");
        forbidden.put("runTaskLater", "Scheduling.globalLater / entityLater");
        forbidden.put("runTaskTimer", "Scheduling.globalTimer / asyncTimer");
        forbidden.put("BukkitRunnable", "Scheduling, which handles Folia");

        // Settings. The record is the schema; a saveDefaultConfig beside it is a second, worse one.
        forbidden.put("saveDefaultConfig", "context.settings(YourSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load");
        forbidden.put("getConfig()", "the settings record's own snapshot");

        // Writing files. YamlStore owns the write-and-move; a second copy is a half-written file the
        // first time a server is killed mid-save.
        forbidden.put("StandardCopyOption.ATOMIC_MOVE", "de.raindancer.core.data.store.YamlStore");
        forbidden.put("Files.createTempFile", "YamlStore, which owns the write-and-move");

        // The menu framework and the prompts.
        forbidden.put("implements InventoryHolder", "de.raindancer.core.ui.menu.Menu");
        forbidden.put("Bukkit.createInventory", "Menu, which owns the window");
        forbidden.put("AnvilInventory", "de.raindancer.core.ui.prompt.ChatPrompts");

        // Sounds and particles, asked for by meaning rather than by name.
        forbidden.put("playSound(", "de.raindancer.core.ui.effect.Effects, with a cue from Cues");
        forbidden.put("spawnParticle(", "de.raindancer.core.ui.effect.Effects");

        // The ambient surfaces. Three bar slots is arbitration nobody can do alone.
        forbidden.put("BossBar.bossBar(", "de.raindancer.core.ui.bossbar.BossBars");
        forbidden.put("getScoreboardManager", "de.raindancer.core.ui.scoreboard.Scoreboards");

        // Wording. load() throws away Core's own wording and every other module's with it.
        forbidden.put("messages.load(", "Messages.defineFrom");
        forbidden.put("ChatColor.", "MiniMessage through Messages");
        forbidden.put("§", "MiniMessage tags — a stray section sign is printed as a section sign");

        // Logging: a module logs to its own channel in the shared file.
        forbidden.put("getLogger()", "context.log()");
        forbidden.put("System.out.print", "context.log()");

        return forbidden;
    }

    /** What is wrong specifically on a screen, where Core has a page for it already. */
    private static Map<String, String> screenOnly() {
        Map<String, String> forbidden = new LinkedHashMap<>();
        forbidden.put("new ItemStack(", "de.raindancer.core.ui.menu.Icons");
        forbidden.put("RED_CANDLE", "de.raindancer.core.ui.choose.AmountChooser — a number is picked, not nudged");
        forbidden.put("GREEN_CANDLE", "AmountChooser — see above");
        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    default void theScanSeesSomething() {
        assertThat(sourcesUnder(moduleSource()))
                .as("java files under %s", moduleSource())
                .isNotEmpty();
    }

    @Test
    @DisplayName("nothing here is a second copy of something Core already owns")
    default void nothingIsRebuilt() {
        assertThat(offences(sourcesUnder(moduleSource()), wheelsAlreadyRound()))
                .as("use Core instead")
                .isEmpty();
    }

    @Test
    @DisplayName("no screen hand-builds what Core's menus and choosers already do")
    default void screensUseCore() {
        assertThat(offences(sourcesUnder(screenSource()), screenOnly()))
                .as("use Core's menu framework and choosers instead")
                .isEmpty();
    }

    private List<String> offences(List<Source> sources, Map<String, String> forbidden) {
        List<String> found = new ArrayList<>();
        for (Source source : sources) {
            for (Map.Entry<String, String> rule : forbidden.entrySet()) {
                if (!source.body().contains(rule.getKey())) {
                    continue;
                }
                if (rule.getValue().equals(allowed().get(source.name()))
                        || allowed().containsKey(source.name() + " :: " + rule.getKey())) {
                    continue;
                }
                found.add(source.name() + " uses '" + rule.getKey() + "' — use " + rule.getValue());
            }
        }
        return found;
    }
}
