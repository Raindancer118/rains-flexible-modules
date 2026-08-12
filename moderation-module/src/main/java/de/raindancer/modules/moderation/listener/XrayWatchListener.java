package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.MinedBlock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watching what gets mined, for {@link de.raindancer.modules.moderation.service.XrayDetectionService}.
 *
 * <h2>Why placed ore does not count</h2>
 * A player who places a block of their own ore back down — decoration, a half-finished build, moving a
 * stack between chests through the world — and then breaks it again is not mining anything. Counted as
 * ore mined, it would nudge somebody's ratio up for doing nothing suspicious at all, so every ore block
 * placed is remembered until it is broken, and a break at that exact spot is skipped entirely: neither
 * ore nor stone, because it was not natural mining either way.
 *
 * <h2>Why the remembered set stays small</h2>
 * Only ore blocks are tracked, and only until they are broken again — an ordinary player places very
 * few of these, and the set is never asked to remember a whole build.
 *
 * <h2>Why ore already sitting in the open does not count either</h2>
 * A lush cave, an ordinary cavern, a ravine wall — anywhere ore is exposed before anybody touches it —
 * is found by looking, not by digging, and the whole premise behind the ratio and the approach signal is
 * that x-ray changes which blocks somebody chooses to break <em>through</em> to reach ore that was not
 * visible. Counting open faces is not enough on its own to tell those two apart, though: an ore block
 * embedded in a cave wall usually only borders the void on the one face actually facing the cavity, every
 * other face is still solid rock — and a tunnel dug straight at hidden ore also only ever opens the one
 * face the player is standing at when it finally breaks. The two look identical by face count alone.
 * What actually differs is whether that opening existed <em>before</em> the player did anything, which is
 * exactly what {@link #openFacesNotDugByPlayer} checks: a face is only "already open" if it is not one
 * of the player's own last {@link #RECENT_BREAK_MEMORY} breaks. A tunnelled-through face is excluded,
 * because the player made it; a face that was air, water or anything else walkable before they arrived is
 * not, however many or few of the six there turn out to be. One such face is enough — a player who
 * happened onto an exposed vein while exploring a cave should never see the same warning a wall-hacking
 * miner does.
 *
 * <h2>Why a whole vein is one find, when {@code xray.veinminer-mode} is on</h2>
 * A vein-mining plugin turns one block broken into a whole connected deposit breaking with it, all in
 * the same instant — which is a single decision by the player, not several. Counted as several separate
 * finds, a deposit of six exposed-just-enough-to-need-one-open-face ore blocks reads as six suspicious
 * events instead of one, for a player who clicked exactly once. See {@link #withinTheSameVein}: every
 * watched-ore break from the same player, of the same material, arriving within
 * {@link #VEIN_WINDOW_MILLIS} of the last one credited, is treated as the same chain reaction and
 * quietly skipped — only the block that started it is ever scored.
 */
public final class XrayWatchListener implements IModerationListener {

    /**
     * Open faces not dug by the player themselves, at or above this many, mean "already exposed" —
     * see the class note. One is enough: once self-dug faces are excluded, any remaining open face was
     * there before the player arrived, which is already the whole condition for "found, not detected".
     */
    private static final int OPEN_FACES_COUNTED_AS_EXPOSED = 1;

    /**
     * How many of a player's most recent breaks — of any block, not only ore — are remembered for
     * telling their own tunnel apart from a pre-existing opening, see {@link #openFacesNotDugByPlayer}.
     *
     * <p>Generous rather than exact: a straight tunnel to one deep, isolated ore can run for dozens of
     * blocks, and a shorter memory would start crediting the tail of a player's own dig as a natural
     * opening the moment it scrolled out of this window.
     */
    private static final int RECENT_BREAK_MEMORY = 64;

    private static final BlockFace[] SIDES = {
            BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST,
            BlockFace.WEST,
    };

    /**
     * How long after crediting one ore block a same-material break from the same player is still
     * treated as the tail of the same chain reaction rather than a fresh find.
     *
     * <p>Generous rather than exact, the same reasoning as {@code MiningTrail}'s own step distance: a
     * large vein a plugin has to break block by block behind the scenes can genuinely take a few
     * server ticks to finish, and a window even a little too short would start crediting the tail of
     * one click as though it were several.
     */
    private static final long VEIN_WINDOW_MILLIS = 250;

    private final ModerationServices services;

    /** Where a player has placed one of the watched ores, keyed by world and coordinates. */
    private final Set<String> placedOre = ConcurrentHashMap.newKeySet();

    /**
     * Per player, the material and moment of the last watched-ore break actually credited — what
     * {@link #withinTheSameVein} compares the next one against.
     */
    private final Map<UUID, VeinCredit> lastCredited = new ConcurrentHashMap<>();

    /** Per player, the positions of their own last {@link #RECENT_BREAK_MEMORY} breaks, oldest first. */
    private final Map<UUID, Deque<String>> recentBreaks = new ConcurrentHashMap<>();

    private record VeinCredit(String material, long atEpochMillis) {
    }

    public XrayWatchListener(ModerationServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isWatchedOre(event.getBlock().getType())) {
            placedOre.add(keyOf(event.getBlock().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        String key = keyOf(block.getLocation());

        if (placedOre.remove(key)) {
            // Their own placement, coming back out. Not mining, in either direction.
            return;
        }
        if (player.hasPermission(SuspiciousCommandListener.BYPASS)) {
            return;
        }
        // Remembered before the exposure check below reads it — not after — so a face this exact break
        // just opened is never mistaken for one that was already there.
        rememberBreak(player.getUniqueId(), block);
        // Read before mined() is called, and never after: the block is still standing at MONITOR
        // priority — Bukkit only actually removes it once every handler has had its say — so this is
        // exactly the state the player found, not whatever the break itself changes about it.
        boolean isOre = isWatchedOre(block.getType());
        if (isOre && openFacesNotDugByPlayer(player.getUniqueId(), block) >= OPEN_FACES_COUNTED_AS_EXPOSED) {
            return;
        }
        if (isOre && services.config().xrayVeinminerModeEnabled()) {
            if (withinTheSameVein(player.getUniqueId(), block.getType())) {
                return;
            }
            lastCredited.put(player.getUniqueId(),
                    new VeinCredit(block.getType().name(), System.currentTimeMillis()));
        }
        services.xrayDetection().mined(player.getUniqueId(), player.getName(),
                new MinedBlock(block.getWorld().getName(), block.getX(), block.getY(), block.getZ(),
                        block.getType().name()));
    }

    /**
     * Whether this ore break is the tail of a chain a vein-mining plugin already started, rather than
     * a new find of its own.
     *
     * <p>The window slides forward on every block credited to the chain, not fixed to when the vein
     * started — see the field note on {@link #lastCredited}. A vein of any real size is still one find
     * from the moment the first block broke to the moment the last one does, however many ticks that
     * chain reaction actually takes.
     */
    private boolean withinTheSameVein(UUID player, Material material) {
        VeinCredit last = lastCredited.get(player);
        return last != null && last.material().equals(material.name())
                && System.currentTimeMillis() - last.atEpochMillis() <= VEIN_WINDOW_MILLIS;
    }

    private boolean isWatchedOre(Material material) {
        for (String name : services.config().xrayOres()) {
            if (name != null && name.equalsIgnoreCase(material.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * How many of the six neighbouring blocks were already open — air, water, anything walkable —
     * before this player touched anything, as opposed to open because they just mined their way there.
     *
     * <p>{@code isPassable()} rather than a narrower "is this air" on purpose: a diamond exposed into
     * a flooded pocket of an underwater cave is exactly as visible as one exposed into dry open space,
     * and treating water as "still solid" for this one question would mark every underwater find as
     * suspicious for no reason that has anything to do with x-ray.
     */
    private int openFacesNotDugByPlayer(UUID player, Block block) {
        int open = 0;
        for (BlockFace side : SIDES) {
            Block neighbour = block.getRelative(side);
            if (neighbour.isPassable() && !dugByPlayer(player, neighbour)) {
                open++;
            }
        }
        return open;
    }

    private void rememberBreak(UUID player, Block block) {
        Deque<String> recent = recentBreaks.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        recent.addLast(keyOf(block.getLocation()));
        while (recent.size() > RECENT_BREAK_MEMORY) {
            recent.removeFirst();
        }
    }

    private boolean dugByPlayer(UUID player, Block block) {
        Deque<String> recent = recentBreaks.get(player);
        return recent != null && recent.contains(keyOf(block.getLocation()));
    }

    private static String keyOf(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
    }

    @Override
    public void forget(UUID player) {
        lastCredited.remove(player);
        recentBreaks.remove(player);
        services.xrayDetection().forget(player);
    }

    @Override
    public String describe() {
        return "watching mined blocks for a pattern that looks like x-ray";
    }
}
