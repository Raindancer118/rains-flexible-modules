package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.choose.MobChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.build.Veins;
import de.raindancer.core.world.spawn.Wave;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The world tools: putting ore in the ground, and creatures on top of it.
 *
 * <h2>Where it acts</h2>
 * At the block the moderator is looking at, not at their feet. Everything here is aimed, and aiming is
 * what a crosshair is for — a tool that acts underfoot cannot be pointed into a cave from the ledge
 * above it, which is most of what somebody wants from an ore vein.
 *
 * <p>Out of range, it says so rather than acting somewhere arbitrary. The version that falls back to
 * the player's own position is the version that buries a vein under the person who was aiming at the
 * sky, and they will never find it.
 *
 * <h2>Why the two halves are guarded differently</h2>
 * Ore is a mod's and creatures are an admin's, and it is the right split. Burying ore costs nothing
 * anybody had and touches only ground the world generated; the worst version of it is a mod being
 * over-friendly to one player, which is a conversation. A wave arrives around somebody who did not ask
 * for it, can kill them and everything they were carrying, and is the one tool here whose effect
 * outlives the click.
 *
 * <p>Both are shown to everybody, greyed with the reason — the grammar this whole repository keeps to.
 * A trial mod who cannot see the wave button cannot learn that it exists, and "why can I not see it"
 * has no answer on screen.
 */
public final class WorldToolsMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** How far a moderator can point. Beyond this a crosshair is a guess. */
    private static final int REACH = 64;

    /** What a vein and a pack are on first opening. Changed with the + and − pairs. */
    private int veinSize = 12;
    private String ore = "IRON_ORE";

    private String creature = "zombie";
    private int packSize = 6;
    private int packs = 1;
    private int everySeconds = 20;

    public WorldToolsMenu(ModerationServices services, Player viewer, Menu parent) {
        super(services, viewer, parent);
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>World tools");
    }

    @Override
    public String breadcrumb() {
        return "World tools";
    }

    @Override
    protected void render() {
        Location aimed = aimedAt();

        // ── where it would happen ─────────────────────────────────────────────────────────────
        band(MenuLayout.WHO, 4, Icons.of(Material.COMPASS, "<white>Where you are looking",
                aimed == null
                        ? List.of("<red>Nothing within " + REACH + " blocks.",
                        "<dark_gray>Point at the ground and open this again.")
                        : List.of("<gray>" + aimed.getBlockX() + ", " + aimed.getBlockY() + ", "
                                + aimed.getBlockZ(),
                        "<gray>" + readable(aimed),
                        "",
                        "<dark_gray>Everything here happens there.")));

        // ── the ore vein ──────────────────────────────────────────────────────────────────────
        boolean mayOre = may(ModerationPermission.SPAWN_ORE);
        band(MenuLayout.RULES, 1, mayOre && aimed != null,
                Icons.of(oreIcon(), "<yellow>Bury a vein of " + words(ore),
                        "<gray>" + veinSize + " blocks, in the ground you are looking at.",
                        "<gray>Only natural stone is replaced — never anything built.",
                        "",
                        "<dark_gray>Left click to bury it.",
                        "<dark_gray>Right click to change the ore."),
                aimed == null ? "Point at some ground first" : "For whoever may bury ore",
                click -> {
                    if (click.isRightClick()) {
                        nextOre();
                        refresh();
                        return;
                    }
                    buryIt();
                });

        // Core's AmountChooser rather than a pair of nudge buttons. Nudging is forty clicks to reach
        // sixty, and the version of this page that had a +4 and a -4 on it was the reason somebody
        // asked for a better one: ±1, ±10, ±100, jump to either end, and nothing happens until Accept.
        band(MenuLayout.RULES, 3, mayOre,
                Icons.of(Material.PAPER, "<yellow>How big",
                        "<gray>" + veinSize + " blocks.",
                        "",
                        "<dark_gray>Click to choose a number."),
                "For whoever may bury ore",
                click -> amount("Blocks in the vein", veinSize, 1,
                        de.raindancer.core.world.build.OreVein.MOST_BLOCKS,
                        chosen -> veinSize = chosen));

        // ── the creatures ─────────────────────────────────────────────────────────────────────
        boolean mayMobs = may(ModerationPermission.SPAWN_MOBS);

        band(MenuLayout.RULES, 6, mayMobs,
                Icons.of(creatureIcon(), "<yellow>What turns up",
                        "<gray>" + words(creature) + ".",
                        "",
                        "<dark_gray>Click to choose something else."),
                "Packs and waves are an admin's",
                // anything(), not toFight(): every creature the server knows, in its drawers.
                // The flat "what can fight" list was two decisions at once — it left out the golems,
                // and several hundred names with no headings between them is a wall nobody can read.
                // The drawers are the part that makes it usable; leaving something out is not.
                click -> MobChooser.anything(viewer, services().brand(), this,
                        "What should turn up?",
                        chosen -> {
                            // Reopened rather than refreshed: the chooser closed the window on its
                            // way out, so there is nothing left to refresh.
                            //
                            // And the page it reopens has to be *this* page. The first version opened
                            // a plain new one and set the creature on the instance that was going
                            // away, so every choice was thrown out and every wave was zombies — the
                            // defaults, faithfully, every time.
                            creature = chosen;
                            reopenCarrying();
                        }).open());

        band(MenuLayout.LAND, 1, mayMobs && aimed != null,
                Icons.of(creatureIcon(), "<yellow>Send a pack",
                        "<gray>" + packSize + " × " + words(creature) + ", around where you look.",
                        "<gray>They arrive at once, in a ring.",
                        "",
                        "<dark_gray>Left click to send it.",
                        "<dark_gray>Or right click to choose how many."),
                aimed == null ? "Point somewhere first" : "Packs and waves are an admin's",
                click -> {
                    if (click.isRightClick()) {
                        amount("Creatures in the pack", packSize, 1, Wave.MOST_PER_PACK,
                                chosen -> packSize = chosen);
                        return;
                    }
                    sendPack();
                });

        // ── the wave, which is the one thing here that keeps happening ────────────────────────
        boolean waveRunning = services().worldTools().hasWaveRunning(viewer.getUniqueId());
        if (waveRunning) {
            // The same slot the start button uses, so one place answers "what is this wave doing?"
            // and the button there is always the thing to press next.
            //
            // Deliberately *not* the danger slot and deliberately unconfirmed. That slot is for
            // something irreversible, and this is the opposite — it is the undo. A stop button behind
            // a confirmation is a tool that takes two clicks in the one moment somebody is panicking.
            band(MenuLayout.LAND, 3, true,
                    Icons.of(Material.BARRIER, "<red>Stop the wave",
                            "<gray>" + services().worldTools().packsLeft(viewer.getUniqueId())
                                    + " pack(s) still to come.",
                            "<gray>What has already arrived stays where it is.",
                            "",
                            "<dark_gray>Click to stop it."),
                    "",
                    click -> {
                        int stopped = services().worldTools().stopWave(viewer.getUniqueId());
                        tell("moderation.world.wave-stopped", "count", stopped);
                        refresh();
                    });
        } else {
            band(MenuLayout.LAND, 3, mayMobs && aimed != null,
                    Icons.of(Material.BELL, "<yellow>Start a wave",
                            "<gray>" + packs + " pack(s) of " + packSize + " × " + words(creature) + ",",
                            "<gray>" + everySeconds + " seconds apart — "
                                    + (packs * packSize) + " in total.",
                            "",
                            "<dark_gray>Left click to start it.",
                            "<dark_gray>Or right click to choose how many packs."),
                    aimed == null ? "Point somewhere first" : "Packs and waves are an admin's",
                    click -> {
                        if (click.isRightClick()) {
                            amount("Packs in the wave", packs, 1, Wave.MOST_PACKS,
                                    chosen -> packs = chosen);
                            return;
                        }
                        startWave();
                    });

            band(MenuLayout.LAND, 5, mayMobs,
                    Icons.of(Material.CLOCK, "<yellow>How far apart",
                            "<gray>" + everySeconds + " seconds between packs.",
                            "",
                            "<dark_gray>Click to choose a number."),
                    "Packs and waves are an admin's",
                    click -> amount("Seconds between packs", everySeconds, 1, 300,
                            chosen -> everySeconds = chosen));
        }
    }

    /**
     * Core's number screen, over one of this page's values.
     *
     * <p>Every amount on this page goes through it. The chooser closes the window when it accepts, so
     * the page is rebuilt carrying everything that was on it — the same reason the mob chooser has to,
     * and the same bug if it is forgotten: the value is set on an instance that is going away.
     */
    private void amount(String label, int current, int least, int most,
                        java.util.function.IntConsumer chosen) {
        new de.raindancer.core.ui.choose.AmountChooser(viewer, services().brand(), this, label,
                current, least, most, picked -> {
                    chosen.accept(picked);
                    reopenCarrying();
                }).open();
    }

    /** This page again, with everything that was on it. See the mob chooser for why. */
    private void reopenCarrying() {
        WorldToolsMenu again = new WorldToolsMenu(services(), viewer, parent());
        again.veinSize = veinSize;
        again.ore = ore;
        again.creature = creature;
        again.packSize = packSize;
        again.packs = packs;
        again.everySeconds = everySeconds;
        again.open();
    }

    // ────────────────────────────────────────────────────────────────────────── doing it

    private void buryIt() {
        Location aimed = aimedAt();
        if (aimed == null) {
            tell("moderation.world.nothing-aimed-at");
            return;
        }
        var placed = services().worldTools().vein(viewer, aimed, ore, veinSize);
        if (placed.isEmpty()) {
            // Says which "nothing happened" this is. "It did not work" about a tool that refuses to
            // touch built blocks is a bug report; "there was nothing there it would replace" is an
            // instruction to aim somewhere else.
            tell("moderation.world.nothing-to-replace");
            return;
        }
        tell("moderation.world.vein-placed", "count", placed.blocks(), "ore", words(ore));
    }

    private void sendPack() {
        Location aimed = aimedAt();
        if (aimed == null) {
            tell("moderation.world.nothing-aimed-at");
            return;
        }
        var arrived = services().worldTools().pack(viewer, aimed, List.of(creature), packSize, 5);
        if (arrived.isEmpty()) {
            tell("moderation.world.nothing-arrived");
            return;
        }
        tell("moderation.world.pack-sent", "count", arrived.spawned(), "what", words(creature));
    }

    private void startWave() {
        Location aimed = aimedAt();
        if (aimed == null) {
            tell("moderation.world.nothing-aimed-at");
            return;
        }
        Wave wave = Wave.of(List.of(creature), packs, packSize, 8, everySeconds * 20L);
        if (!services().worldTools().startWave(viewer, aimed, wave)) {
            tell("moderation.world.wave-already-running");
            return;
        }
        tell("moderation.world.wave-started", "count", wave.total(), "packs", wave.packs().size());
        refresh();
    }

    // ────────────────────────────────────────────────────────────────────────── the aiming

    /**
     * The block being looked at, or null when that is nothing within reach.
     *
     * <p>Null rather than the player's own position, deliberately: falling back underfoot buries a
     * vein under somebody who was aiming at the sky, and they will never find it.
     */
    private Location aimedAt() {
        var block = viewer.getTargetBlockExact(REACH);
        return block == null ? null : block.getLocation();
    }

    private String readable(Location at) {
        String material = at.getBlock().getType().name();
        return Veins.isNatural(material)
                ? words(material) + " — a vein would take"
                : words(material) + " — a vein would not touch this";
    }

    /**
     * The chosen creature's own spawn egg.
     *
     * <p>The buttons showed a zombie head whatever was picked, so the page said "zombie" while meaning
     * "ghast" — and the icon is the thing anybody actually reads on a button. Core's catalogue answers
     * it, including for the handful with no egg at all.
     */
    private Material creatureIcon() {
        Material found = Material.matchMaterial(
                de.raindancer.core.ui.choose.MobCatalogue.iconFor(creature));
        return found == null ? Material.ZOMBIE_HEAD : found;
    }

    private Material oreIcon() {
        Material found = Material.matchMaterial(ore);
        return found == null ? Material.IRON_ORE : found;
    }

    /** The next ore in Core's list, so the button cycles rather than opening a page for eleven items. */
    private void nextOre() {
        List<String> ores = new ArrayList<>(Veins.ores());
        int at = ores.indexOf(ore);
        ore = ores.get((at + 1) % ores.size());
    }

    /** {@code IRON_ORE} reads as "iron ore". */
    private static String words(String constant) {
        return constant == null ? "" : constant.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    @Override
    public String describe() {
        return "burying ore, and calling up packs and waves, where the moderator is looking";
    }
}
