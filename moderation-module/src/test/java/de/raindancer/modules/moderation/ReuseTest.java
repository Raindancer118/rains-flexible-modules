package de.raindancer.modules.moderation;

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
 * deliberately. It is made at half past eleven by somebody who needs a duration parsed, does not know
 * {@code Times} exists, and writes fifteen lines that understand {@code s m h d} and not {@code 2min} —
 * and now the server has two ideas of how long "2M" is. That happened in five separate plugins here,
 * which is why {@code Durations} is now four lines that delegate.
 *
 * <p>Every entry below names something Core owns and a shape the module would take if it started
 * owning it too. This is deliberately about <em>the moderation module</em>: not a general lint, but a
 * list of the specific wheels within reach of this particular code.
 *
 * @see <a href="file:../../MODULE-LAYOUT.md">MODULE-LAYOUT.md</a> — "What belongs in RainsCore instead"
 */
class ReuseTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/moderation");

    private record Source(String name, String body) {
    }

    private static List<Source> module() {
        try (Stream<Path> files = Files.walk(ROOT)) {
            List<Source> found = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                found.add(new Source(ROOT.relativize(file).toString(), Files.readString(file)));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }

    /** What the module must not grow its own version of, and what it should use instead. */
    private static Map<String, String> wheelsAlreadyRound() {
        Map<String, String> forbidden = new LinkedHashMap<>();

        // Durations. Times understands months against minutes, which is the distinction a ban command
        // cannot afford to get wrong, and five plugins here each got it wrong differently.
        forbidden.put("Duration.parse(", "de.raindancer.core.world.time.Times.parse / Durations.parse");
        forbidden.put("ChronoUnit.", "Times, which already reads and writes every unit somebody types");

        // The write-to-a-temporary-then-move dance. Written seven times inside Core before YamlStore
        // existed; each copy was a chance to leave half a file where everybody's reports used to be.
        forbidden.put("StandardCopyOption.ATOMIC_MOVE", "de.raindancer.core.data.store.YamlStore");
        forbidden.put("Files.createTempFile", "YamlStore, which owns the write-and-move");

        // The menu framework. The version this replaced had one per plugin, which is why the same
        // server looked like five plugins.
        forbidden.put("implements InventoryHolder", "de.raindancer.core.ui.menu.Menu");
        forbidden.put("Bukkit.createInventory", "Menu, which owns the window");

        // Punishments themselves. If the module kept its own set, a server that removed it would stop
        // enforcing every ban it had already handed out.
        forbidden.put("class Punishments", "de.raindancer.core.moderation.punishment.Punishments");
        forbidden.put("setBanned(", "Punishments plus VanillaBanBridge, which keeps both lists in step");
        forbidden.put("getBanList(", "VanillaBanBridge");

        // Vanish and inventory viewing. Both are Core's, and both are the sort of thing two plugins
        // each doing it means a player who is invisible to one and not the other.
        forbidden.put("hidePlayer(", "de.raindancer.core.moderation.vanish.Vanish");
        forbidden.put("showPlayer(", "Vanish");

        // Item serialisation. It moved *out* of moderation into core.data.nbt precisely because a
        // codec did not belong here.
        forbidden.put("serializeAsBytes", "de.raindancer.core.data.nbt.ItemText / ItemBytes");
        forbidden.put("BukkitObjectOutputStream", "ItemText");

        // Effects, healing, feeding, gamemode. PlayerAdmin owns the lot, with an Outcome per call.
        forbidden.put("addPotionEffect(", "de.raindancer.core.moderation.players.PlayerAdmin");
        forbidden.put("setGameMode(", "PlayerAdmin.gamemode");

        // Logging and scheduling.
        forbidden.put("getLogger()", "context.log(), which is this module's channel in the shared file");
        forbidden.put("runTaskTimer", "de.raindancer.core.platform.util.Scheduling, which handles Folia");
        forbidden.put("getScheduler()", "Scheduling");

        return forbidden;
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(20);
        assertThat(module()).anyMatch(source -> source.name().endsWith("ModerationModule.java"));
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
                        + "answer to. See MODULE-LAYOUT.md: if two plugins could want it, it is not "
                        + "module code")
                .isEmpty();
    }

    @Test
    @DisplayName("the module actually uses what Core offers, rather than merely avoiding rebuilding it")
    void coreIsActuallyUsed() {
        // The other half of the same rule. Avoiding a duplicate by doing nothing at all would pass the
        // test above and leave the module poorer than the plugin it replaces.
        String everything = String.join("\n", module().stream().map(Source::body).toList());

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("punishment.Punishments", "the punishments themselves");
        expected.put("punishment.PunishmentGuard", "what stops a banned player joining");
        expected.put("punishment.VanillaBanBridge", "keeping the server's own ban list in step");
        expected.put("vanish.Vanish", "going invisible");
        expected.put("players.PlayerAdmin", "healing, feeding, gamemode and effects");
        expected.put("invsee.Inventories", "looking in somebody's inventory");
        expected.put("audit.Audit", "the trail of who did what");
        expected.put("data.store.YamlStore", "writing the reports and the notes");
        expected.put("ui.choose.PlayerDirectory", "picking a player who is probably offline");
        expected.put("ui.menu.Menu", "the screens");
        expected.put("ui.prompt.ChatPrompts", "asking somebody to type a reason");
        expected.put("world.time.Times", "how long a punishment is for, in words");

        List<String> unused = new ArrayList<>();
        expected.forEach((type, what) -> {
            if (!everything.contains(type)) {
                unused.add(type + " (" + what + ")");
            }
        });

        assertThat(unused)
                .as("these are in RainsCore for exactly this module to use, and it is not using them")
                .isEmpty();
    }
}
