package de.raindancer.modules.moderation.command;

import de.raindancer.core.world.spawn.Wave;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /vein} and {@code /mob} — the world tools, typed.
 *
 * <h2>Why these earn a place beside the page</h2>
 * Because they take arguments a menu is slow at. {@code /vein diamond_ore 40} is one line for what is
 * four clicks and two screens, and somebody building an event runs it twenty times with different
 * numbers. The page stays for the times you do not know what you want yet — it shows the ore, the
 * creature and the numbers together, and lets you change one and look.
 *
 * <p>Everything still happens <b>where you are looking</b>, exactly as on the page. That is the one
 * argument neither form asks for, because a crosshair says it better than three numbers.
 *
 * <h2>Why {@code /mob} takes a subcommand and {@code /vein} does not</h2>
 * A pack and a wave are the same decision with one difference — whether it arrives at once or over
 * time — so they belong under one word with that difference named. A vein has no second form.
 */
public final class WorldToolCommands {

    private WorldToolCommands() {
    }

    /** How far a moderator can point, the same as the page. */
    private static final int REACH = 64;

    /** {@code /vein [ore] [size]} — bury one where you are looking. */
    public static final class Vein extends StaffCommand {

        public Vein(Supplier<ModerationServices> services) {
            super(services, ModerationPermission.SPAWN_ORE);
        }

        @Override
        public String describe() {
            return "bury a vein of ore in the ground you are looking at";
        }

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            ModerationServices moderation = services();
            CommandSender sender = source.getSender();
            if (!(sender instanceof Player player)) {
                moderation.messages().send(sender, "moderation.world.players-only");
                return;
            }
            Location aimed = aimedAt(player);
            if (aimed == null) {
                moderation.messages().send(sender, "moderation.world.nothing-aimed-at");
                return;
            }
            String ore = args.length > 0 ? args[0].toUpperCase(Locale.ROOT) : "IRON_ORE";
            int size = number(args, 1, 12);

            var placed = moderation.worldTools().vein(player, aimed, ore, size);
            if (placed.isEmpty()) {
                moderation.messages().send(sender, "moderation.world.nothing-to-replace");
                return;
            }
            moderation.messages().send(sender, "moderation.world.vein-placed",
                    "count", placed.blocks(), "ore", words(ore));
        }

        /** The ores Core will actually bury, so a typo is caught by completion rather than by silence. */
        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            if (args.length <= 1) {
                String typed = args.length == 1 ? args[0].toUpperCase(Locale.ROOT) : "";
                return de.raindancer.core.world.build.Veins.ores().stream()
                        .filter(ore -> ore.startsWith(typed))
                        .toList();
            }
            return args.length == 2 ? List.of("8", "16", "32", "64") : List.of();
        }
    }

    /** {@code /mob pack|wave <creature> [how many] [packs] [seconds]}. */
    public static final class Mob extends StaffCommand {

        public Mob(Supplier<ModerationServices> services) {
            super(services, ModerationPermission.SPAWN_MOBS);
        }

        @Override
        public String describe() {
            return "call up a pack of creatures, or a wave of them over time";
        }

        @Override
        public void execute(CommandSourceStack source, String[] args) {
            ModerationServices moderation = services();
            CommandSender sender = source.getSender();
            if (!(sender instanceof Player player)) {
                moderation.messages().send(sender, "moderation.world.players-only");
                return;
            }
            if (args.length == 0) {
                moderation.messages().send(sender, "moderation.usage",
                        "usage", "/mob pack|wave somebody [how many] [packs] [seconds]");
                return;
            }
            String what = args[0].toLowerCase(Locale.ROOT);
            // stop is here rather than under /worldtools because it is the one thing somebody needs in
            // a hurry, and opening a page to reach it is exactly the wrong shape for that.
            if (what.equals("stop")) {
                int stopped = moderation.worldTools().stopWave(player.getUniqueId());
                moderation.messages().send(sender, "moderation.world.wave-stopped", "count", stopped);
                return;
            }
            if (!what.equals("pack") && !what.equals("wave")) {
                moderation.messages().send(sender, "moderation.usage",
                        "usage", "/mob pack|wave|stop somebody [how many] [packs] [seconds]");
                return;
            }
            Location aimed = aimedAt(player);
            if (aimed == null) {
                moderation.messages().send(sender, "moderation.world.nothing-aimed-at");
                return;
            }
            String creature = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "zombie";
            int howMany = number(args, 2, 6);

            if (what.equals("pack")) {
                var arrived = moderation.worldTools()
                        .pack(player, aimed, List.of(creature), howMany, 5);
                moderation.messages().send(sender,
                        arrived.isEmpty() ? "moderation.world.nothing-arrived"
                                : "moderation.world.pack-sent",
                        "count", arrived.spawned(), "what", words(creature));
                return;
            }
            int packs = number(args, 3, 3);
            int seconds = number(args, 4, 20);
            Wave wave = Wave.of(List.of(creature), packs, howMany, 8, seconds * 20L);
            if (!moderation.worldTools().startWave(player, aimed, wave)) {
                moderation.messages().send(sender, "moderation.world.wave-already-running");
                return;
            }
            moderation.messages().send(sender, "moderation.world.wave-started",
                    "count", wave.total(), "packs", wave.packs().size());
        }

        /**
         * Every creature the server knows, which is the same list the chooser offers.
         *
         * <p>Not a shortlist: the page stopped filtering for the same reason, and a completion that
         * offers less than the screen is one that teaches somebody the wrong set.
         */
        @Override
        public Collection<String> suggest(CommandSourceStack source, String[] args) {
            if (args.length <= 1) {
                String typed = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
                return List.of("pack", "wave", "stop").stream()
                        .filter(one -> one.startsWith(typed))
                        .toList();
            }
            if (args.length == 2) {
                String typed = args[1].toLowerCase(Locale.ROOT);
                List<String> found = new ArrayList<>();
                for (String creature : de.raindancer.core.ui.choose.MobChooser
                        .everythingOnThisServer().all()) {
                    if (creature.startsWith(typed)) {
                        found.add(creature);
                    }
                    if (found.size() >= 60) {
                        break;      // a completion list nobody can read is a completion list
                    }
                }
                return found;
            }
            return List.of();
        }
    }

    // ────────────────────────────────────────────────────────────────────── shared

    /** The block being looked at, or null — never a fallback to underfoot. See the page. */
    private static Location aimedAt(Player player) {
        Block block = player.getTargetBlockExact(REACH);
        return block == null ? null : block.getLocation();
    }

    /** One numeric argument, or the default. A word where a number goes is the default, not an error. */
    private static int number(String[] args, int at, int fallback) {
        if (args.length <= at) {
            return fallback;
        }
        try {
            return Integer.parseInt(args[at]);
        } catch (NumberFormatException notANumber) {
            // Core clamps everything anyway, so the worst a stray word costs is the default — which
            // is a better answer than refusing the whole command over one argument.
            return fallback;
        }
    }

    private static String words(String constant) {
        return constant == null ? "" : constant.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
