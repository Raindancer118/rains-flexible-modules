package de.raindancer.modules.hungergames;

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
 * <h2>Why this test matters more here than in any other module</h2>
 * Because this module is a port, and the thing it was ported from had written all of it itself. The plugin
 * this replaces shipped its own menu framework, its own item-stack builder, its own anvil text input, its own
 * confirmation dialog, its own number editor, its own material picker, its own colour picker, its own message
 * table, its own settings system — an 835-line hand-written catalogue with a {@code config.yml} duplicating it
 * — its own sound service, its own particle service, its own name resolver, its own loot manager, its own
 * custom items, its own monster waves, its own chat input handler, and its own write-to-a-temporary-then-move.
 * Every one of those exists in RainsCore, tested, and shared with seven other modules.
 *
 * <p>Which means the failure mode here is not somebody inventing something new. It is somebody porting one
 * more file from the old plugin without noticing that its job is already done — at half past eleven, with
 * thirty files to go, by copying what was there. That is how a server ends up looking like two plugins again,
 * and it is why this is a test rather than a paragraph in a document.
 *
 * <p>The other half of the reason is the owner's instruction for the port, in his words: <em>"use Core's APIs
 * and features where possible."</em> An instruction nothing checks is an instruction that holds for about a
 * fortnight.
 *
 * @see <a href="file:../../MODULE-LAYOUT.md">MODULE-LAYOUT.md</a> — "What belongs in RainsCore instead"
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/hungergames");

    private record Source(String name, String body) {
    }

    /**
     * The file with its comments taken out.
     *
     * <p>This module documents itself at length, and several of those explanations name the very thing they
     * were written to forbid — {@code TeamRegistry}'s note on why the roster moved to Core, the module class's
     * list of what was deliberately not ported. Scanned raw, the explanation trips the rule it explains, and
     * the only way to get the build green would be to delete the reasoning.
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

    /** What the module must not grow its own version of, and what it should reach for instead. */
    private static Map<String, String> wheelsAlreadyRound() {
        Map<String, String> forbidden = new LinkedHashMap<>();

        // Scheduling. A round is a great many timers — the phase clock, the border, supply drops, sponsor
        // tokens, the deathmatch warning, every countdown — and a hand-rolled BukkitRunnable is right on
        // Paper and a crash on Folia, found by exactly one server.
        forbidden.put("getScheduler()", "de.raindancer.core.platform.util.Scheduling");
        forbidden.put("runTaskLater", "Scheduling.globalLater / entityLater");
        forbidden.put("runTaskTimer", "Scheduling.globalTimer / asyncTimer");
        forbidden.put("BukkitRunnable", "Scheduling, which handles Folia");

        // The settings. The record *is* the schema, so a hand-rolled catalogue and a saveDefaultConfig are a
        // second, worse config system — which is precisely what was ported away from.
        forbidden.put("saveDefaultConfig", "context.settings(HungerGamesSettings.class, ...)");
        forbidden.put("reloadConfig()", "SettingsStore.load");
        forbidden.put("getConfig()", "the HungerGamesSettings snapshot");
        forbidden.put("SettingDefinition", "a component of HungerGamesSettings with @Key and @Describe");
        forbidden.put("SettingsRegistry", "de.raindancer.core.data.settings.SettingsSchema");

        // Writing files. YamlStore owns the write-and-move; a second copy is a half-written session.yml the
        // first time a server is killed mid-save.
        forbidden.put("StandardCopyOption.ATOMIC_MOVE", "de.raindancer.core.data.store.YamlStore");
        forbidden.put("Files.createTempFile", "YamlStore, which owns the write-and-move");

        // The menu framework. The old plugin had forty menus over its own base class, which is why the same
        // server looked like five plugins.
        forbidden.put("implements InventoryHolder", "de.raindancer.core.ui.menu.Menu");
        forbidden.put("Bukkit.createInventory", "Menu, which owns the window");
        forbidden.put("new ItemStack(", "de.raindancer.core.ui.menu.Icons");
        forbidden.put("setItemMeta(new ", "Icons, which builds every button on this server");
        // A rule that used to live here and has been removed on purpose: forbidding
        // "super(viewer, brand, parent, 3)" as a stand-in for "a page that hand-builds a confirmation
        // dialog". It cannot express that. Three rows is Menu's ordinary constructor and every legitimate
        // small page matches it — DeathmatchMenu was flagged for being three rows tall.
        //
        // What the rule was actually for is already enforced, and enforced properly, by
        // ScreenGrammarTest.theDangerSlotAlwaysConfirms: every danger( button has to open ConfirmScreen.
        // That asks the real question rather than a proxy for it.
        forbidden.put("AnvilInventory", "de.raindancer.core.ui.prompt.ChatPrompts");

        // The choosers. Ten of the old plugin's forty menus were a grid of something Core already offers a
        // page for, and each was a slightly different grid.
        forbidden.put("MaterialPickerMenu", "de.raindancer.core.ui.choose.ItemChooser");
        forbidden.put("NumberEditorMenu", "de.raindancer.core.ui.choose.AmountChooser");
        forbidden.put("ColorPickerMenu", "a Catalogue of TeamColour, via core.ui.choose");
        forbidden.put("SoundPickerMenu", "de.raindancer.core.ui.choose.SoundChooser");
        forbidden.put("EffectPickerMenu", "de.raindancer.core.ui.choose.EffectChooser");

        // Sounds and particles, asked for by meaning. A plugin that names a Sound is the one whose noises are
        // the only thing on the server that does not follow when an owner rebinds a cue.
        forbidden.put("playSound(", "de.raindancer.core.ui.effect.Effects, with a cue from Cues");
        forbidden.put("spawnParticle(", "de.raindancer.core.ui.effect.Effects");
        forbidden.put("org.bukkit.Sound", "de.raindancer.core.ui.effect.Cues");

        // The ambient surfaces. A player has three bar slots at most, so who wins is arbitration nobody can
        // do alone; and a scoreboard built by hand is one that fights every other plugin's.
        forbidden.put("BossBar.bossBar(", "de.raindancer.core.ui.bossbar.BossBars");
        forbidden.put("getScoreboardManager", "de.raindancer.core.ui.scoreboard.Scoreboards");
        forbidden.put("sendActionBar(", "de.raindancer.core.ui.actionbar.ActionBars");

        // Wording. Messages.defineFrom is the one way a module's bundled messages.yml becomes a floor under
        // the owner's; load() would throw away Core's own wording and every other module's with it.
        forbidden.put("messages.load(", "Messages.defineFrom — load() throws away Core's own wording");
        forbidden.put("ChatColor.", "MiniMessage through Messages, and core.ui.chat.Style");
        forbidden.put("§", "MiniMessage tags — a stray section sign is printed as a section sign");

        // Logging. A module logs to its own channel in the shared file, not to a plugin's logger.
        forbidden.put("getLogger()", "context.log(), this module's channel in the shared file");
        forbidden.put("System.out.print", "context.log()");

        // Loot and items. Core owns loot tables, their tiers and their rolling; and a custom item's identity
        // is a PDC key, which is what CustomItem carries. Recognising a sponsor token by its material alone
        // is the bug that let a stack of the wrong thing be spent.
        forbidden.put("class LootManager", "de.raindancer.core.content.loot.LootTables");
        forbidden.put("class LootConfig", "de.raindancer.core.content.loot.LootTable");
        forbidden.put("NamespacedKey(plugin, \"sponsor", "core.content.items.CustomItem, which owns the key");

        // Teams. The roster is Core's now — one model for a tournament, a clan, a bedwars match and a party.
        // A second registry here would be a second answer to "is this colour free".
        forbidden.put("class TeamRegistry", "de.raindancer.core.social.team.Teams");
        forbidden.put("enum TeamColor", "de.raindancer.core.social.team.TeamColour");

        // Lengths of time, and waiting between things.
        forbidden.put("endsWith(\"d\")", "de.raindancer.core.world.time.Times.parse");
        forbidden.put("TimeUnit.DAYS.toMillis", "Times, which reads and writes what people actually type");

        // Names. Resolving an offline player's name is a blocking call somebody will make on the main thread.
        forbidden.put("getOfflinePlayer(", "de.raindancer.core.ui.choose.PlayerDirectory");

        // The teleport. Putting a tribute somewhere is Travel's, the same code the warps and homes use.
        forbidden.put("teleportAsync", "de.raindancer.core.world.teleport.Travel");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(20);
        assertThat(module()).anyMatch(source -> source.name().endsWith("HungerGamesModule.java"));
    }

    /**
     * The one exception, and the reason it is written as a method rather than as a set of strings.
     *
     * <p>{@code getOfflinePlayer} is forbidden because {@code PlayerDirectory} is the right way to choose a
     * player. Setting somebody's operator flag is not choosing a player: it writes to {@code ops.json}, Core
     * has no API for it and should not grow one — being an operator is the server's own idea of who may do
     * anything, not a permission Core could grant. See {@code HungerGamesWiring.opAccess()}.
     *
     * <p>Narrow on purpose: the exemption is one call in one named method, so a second use of
     * {@code getOfflinePlayer} anywhere — including elsewhere in the same file — still fails this test.
     */
    private static boolean isTheOneAllowedOperatorLookup(String file, String key) {
        return key.equals("getOfflinePlayer(")
                && file.endsWith("HungerGamesWiring.java")
                // Exactly once, and inside the method that explains itself.
                && countOf(file, key) == 1;
    }

    private static int countOf(String file, String key) {
        String body = module().stream()
                .filter(source -> source.name().endsWith(file.substring(file.lastIndexOf('/') + 1)))
                .findFirst().map(Source::body).orElse("");
        int count = 0;
        int at = body.indexOf(key);
        while (at >= 0) {
            count++;
            at = body.indexOf(key, at + 1);
        }
        return count;
    }

    @Test
    @DisplayName("nothing here is a second copy of something Core already owns")
    void nothingIsReinvented() {
        List<String> reinvented = new ArrayList<>();
        for (Source source : module()) {
            for (Map.Entry<String, String> wheel : wheelsAlreadyRound().entrySet()) {
                if (source.body().contains(wheel.getKey())
                        && !isTheOneAllowedOperatorLookup(source.name(), wheel.getKey())) {
                    reinvented.add(source.name() + " uses '" + wheel.getKey()
                            + "' — use " + wheel.getValue());
                }
            }
        }
        assertThat(reinvented)
                .as("every one of these is a second answer to a question this server should have one "
                        + "answer to, and every one of them was written out in the plugin this was ported "
                        + "from")
                .isEmpty();
    }

    @Test
    @DisplayName("the module hands its own wording over to Core's table")
    void theWordingGoesThroughCore() {
        // The inverse of the scan above: it is not enough that a second Messages is absent, the real one has
        // to be reached. A port that simply dropped the wording would pass every rule up to here and answer
        // every line with its own key.
        assertThat(module())
                .as("nothing calls Messages.defineFrom, so this module's messages.yml is never read")
                .anyMatch(source -> source.body().contains("defineFrom"));
    }

    @Test
    @DisplayName("the teams are Core's, and only one class here reaches them")
    void thereIsOneDoorToTheTeams() {
        // Everything that reads or changes a team goes through the session, so there is one place where "who
        // is on which team" is answered. Two ideas of that is a round where the scoreboard and the winner
        // logic disagree about who is still in it.
        List<String> reachingPastIt = new ArrayList<>();
        for (Source source : module()) {
            if (source.name().endsWith("GameSession.java") || source.name().endsWith("TeamRules.java")) {
                continue;   // the door itself, and the policy it is built from
            }
            if (source.body().contains("new Teams(")) {
                reachingPastIt.add(source.name());
            }
        }
        assertThat(reachingPastIt)
                .as("these build a second roster instead of going through the session's")
                .isEmpty();
    }
}
