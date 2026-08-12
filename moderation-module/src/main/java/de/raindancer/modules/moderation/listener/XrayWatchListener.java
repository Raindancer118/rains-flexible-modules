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
 * A lush cave, an ordinary cavern, a ravine wall — anywhere ore is exposed on more than one face before
 * anybody touches it — is found by looking, not by digging, and the whole premise behind the ratio and
 * the approach signal is that x-ray changes which blocks somebody chooses to break <em>through</em> to
 * reach ore that was not visible. A tunnel dug straight at hidden ore only ever opens the one face the
 * player is standing at; a block sitting in a cavern typically has several. See {@link #openFaces}: two
 * or more open faces at the moment of the break is treated as "found, not detected", and never reaches
 * the ratio, the baseline, the trail or a report — a player who happened onto an exposed vein while
 * exploring a cave should never see the same warning a wall-hacking miner does.
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
     * Open faces at or above this many mean "already exposed" — see the class note.
     *
     * <p>One is what an ordinary mining tunnel produces on its own, dug straight at ore nobody could
     * see: the single face the player just broke through from. Two is not something a straight tunnel
     * produces by accident, and is exactly what a block sitting in real open space — a cave wall, a
     * ravine, the inside of a lush cave — looks like instead.
     */
    private static final int OPEN_FACES_COUNTED_AS_EXPOSED = 2;

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
        // Read before mined() is called, and never after: the block is still standing at MONITOR
        // priority — Bukkit only actually removes it once every handler has had its say — so this is
        // exactly the state the player found, not whatever the break itself changes about it.
        boolean isOre = isWatchedOre(block.getType());
        if (isOre && openFaces(block) >= OPEN_FACES_COUNTED_AS_EXPOSED) {
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
     * How many of the six neighbouring blocks are already open — air, water, anything walkable.
     *
     * <p>{@code isPassable()} rather than a narrower "is this air" on purpose: a diamond exposed into
     * a flooded pocket of an underwater cave is exactly as visible as one exposed into dry open space,
     * and treating water as "still solid" for this one question would mark every underwater find as
     * suspicious for no reason that has anything to do with x-ray.
     */
    private static int openFaces(Block block) {
        int open = 0;
        for (BlockFace side : SIDES) {
            if (block.getRelative(side).isPassable()) {
                open++;
            }
        }
        return open;
    }

    private static String keyOf(Location location) {
        return location.getWorld().getUID() + ":" + location.getBlockX() + ","
                + location.getBlockY() + "," + location.getBlockZ();
    }

    @Override
    public void forget(UUID player) {
        lastCredited.remove(player);
        services.xrayDetection().forget(player);
    }

    @Override
    public String describe() {
        return "watching mined blocks for a pattern that looks like x-ray";
    }
}
