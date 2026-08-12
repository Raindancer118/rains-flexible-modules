package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.MinedBlock;
import de.raindancer.modules.moderation.service.XrayDetectionService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What gets past the listener before {@code XrayDetectionService} ever sees it.
 *
 * <h2>Both exemptions tested here are about the same thing</h2>
 * Neither an ore block sitting in the open nor the ninth block of one veinminer click is a
 * <em>find</em> in the sense the ratio and the review screen care about — the first was never hidden,
 * and the second was never individually chosen. Both are excluded before {@link XrayDetectionService}
 * is even asked, rather than taught to it, because the service's whole job is judging finds and
 * neither of these is one.
 */
class XrayWatchListenerTest {

    private static final UUID MOD = UUID.randomUUID();
    private static final World WORLD = mock(World.class);

    /** A block with every neighbour solid — a fresh face broken straight out of untouched stone. */
    private static Block fullyEnclosedOre(Material material) {
        Block block = solidBlock(material);
        for (BlockFace side : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block neighbour = solidBlock(Material.STONE);
            when(block.getRelative(side)).thenReturn(neighbour);
        }
        return block;
    }

    /** The same, but with {@code openSides} of its six faces already open before the break. */
    private static Block partlyExposedOre(Material material, int openSides) {
        Block block = solidBlock(material);
        List<BlockFace> sides = List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
                BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST);
        for (int index = 0; index < sides.size(); index++) {
            Block neighbour = index < openSides ? openBlock() : solidBlock(Material.STONE);
            when(block.getRelative(sides.get(index))).thenReturn(neighbour);
        }
        return block;
    }

    private static Block solidBlock(Material material) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.isPassable()).thenReturn(false);
        when(block.getWorld()).thenReturn(WORLD);
        when(block.getLocation()).thenReturn(new Location(WORLD, 0, 64, 0));
        return block;
    }

    private static Block openBlock() {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(Material.CAVE_AIR);
        when(block.isPassable()).thenReturn(true);
        return block;
    }

    private static Player playerWithId(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn("Mod");
        when(player.hasPermission(SuspiciousCommandListener.BYPASS)).thenReturn(false);
        return player;
    }

    private static ModerationServices servicesWith(ModerationSettings settings,
                                                    XrayDetectionService xrayDetection) {
        ModerationServices services = mock(ModerationServices.class);
        when(services.config()).thenReturn(settings);
        when(services.xrayDetection()).thenReturn(xrayDetection);
        return services;
    }

    private static BlockBreakEvent eventFor(Player player, Block block) {
        return new BlockBreakEvent(block, player);
    }

    @Nested
    @DisplayName("ore already sitting in the open")
    class AlreadyExposed {

        @Test
        @DisplayName("a diamond dug straight out of solid stone is reported as a find")
        void fullyEnclosedIsCounted() {
            XrayDetectionService xray = mock(XrayDetectionService.class);
            XrayWatchListener listener = new XrayWatchListener(
                    servicesWith(ModerationSettings.DEFAULTS, xray));
            Player player = playerWithId(MOD);

            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));

            verify(xray).mined(eq(MOD), eq("Mod"), any(MinedBlock.class));
        }

        @Test
        @DisplayName("a diamond with two open faces already, a cave wall's own shape, is not")
        void twoOpenFacesIsExempt() {
            XrayDetectionService xray = mock(XrayDetectionService.class);
            XrayWatchListener listener = new XrayWatchListener(
                    servicesWith(ModerationSettings.DEFAULTS, xray));
            Player player = playerWithId(MOD);

            listener.onBreak(eventFor(player, partlyExposedOre(Material.DIAMOND_ORE, 2)));

            verify(xray, never()).mined(any(), any(), any());
        }

        @Test
        @DisplayName("one open face — exactly what a mining tunnel produces on its own — still counts")
        void oneOpenFaceStillCounts() {
            XrayDetectionService xray = mock(XrayDetectionService.class);
            XrayWatchListener listener = new XrayWatchListener(
                    servicesWith(ModerationSettings.DEFAULTS, xray));
            Player player = playerWithId(MOD);

            listener.onBreak(eventFor(player, partlyExposedOre(Material.DIAMOND_ORE, 1)));

            verify(xray).mined(eq(MOD), eq("Mod"), any(MinedBlock.class));
        }

        @Test
        @DisplayName("an ordinary stone block is never exempted by this check, however open it is")
        void onlyOreIsEverExempted() {
            XrayDetectionService xray = mock(XrayDetectionService.class);
            XrayWatchListener listener = new XrayWatchListener(
                    servicesWith(ModerationSettings.DEFAULTS, xray));
            Player player = playerWithId(MOD);

            listener.onBreak(eventFor(player, partlyExposedOre(Material.STONE, 4)));

            verify(xray).mined(eq(MOD), eq("Mod"), any(MinedBlock.class));
        }
    }

    @Nested
    @DisplayName("veinminer mode")
    class Veinminer {

        @Test
        @DisplayName("off by default, every block of a chain counts on its own")
        void offByDefaultCountsEveryBlock() {
            XrayDetectionService xray = mock(XrayDetectionService.class);
            ModerationSettings settings = ModerationSettings.DEFAULTS
                    .withXrayVeinminerModeEnabled(false);
            XrayWatchListener listener = new XrayWatchListener(servicesWith(settings, xray));
            Player player = playerWithId(MOD);

            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));
            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));

            verify(xray, times(2)).mined(eq(MOD), eq("Mod"), any(MinedBlock.class));
        }

        @Test
        @DisplayName("on, a second same-material break right after the first is swallowed")
        void onSwallowsTheChain() {
            XrayDetectionService xray = mock(XrayDetectionService.class);
            ModerationSettings settings = ModerationSettings.DEFAULTS
                    .withXrayVeinminerModeEnabled(true);
            XrayWatchListener listener = new XrayWatchListener(servicesWith(settings, xray));
            Player player = playerWithId(MOD);

            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));
            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));
            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));

            verify(xray, times(1)).mined(eq(MOD), eq("Mod"), any(MinedBlock.class));
        }

        @Test
        @DisplayName("on, a different material right after is its own find, not the same chain")
        void onDoesNotSwallowADifferentMaterial() {
            XrayDetectionService xray = mock(XrayDetectionService.class);
            ModerationSettings settings = ModerationSettings.DEFAULTS
                    .withXrayVeinminerModeEnabled(true);
            XrayWatchListener listener = new XrayWatchListener(servicesWith(settings, xray));
            Player player = playerWithId(MOD);

            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));
            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.EMERALD_ORE)));

            verify(xray, times(2)).mined(eq(MOD), eq("Mod"), any(MinedBlock.class));
        }

        @Test
        @DisplayName("on, the same material well after the window has passed is a fresh find again")
        void onCreditsAgainAfterTheWindow() throws InterruptedException {
            XrayDetectionService xray = mock(XrayDetectionService.class);
            ModerationSettings settings = ModerationSettings.DEFAULTS
                    .withXrayVeinminerModeEnabled(true);
            XrayWatchListener listener = new XrayWatchListener(servicesWith(settings, xray));
            Player player = playerWithId(MOD);

            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));
            Thread.sleep(300);
            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));

            verify(xray, times(2)).mined(eq(MOD), eq("Mod"), any(MinedBlock.class));
        }

        @Test
        @DisplayName("forgetting a player who left clears the chain, so nothing carries into their next session")
        void forgettingClearsTheChain() {
            XrayDetectionService xray = mock(XrayDetectionService.class);
            ModerationSettings settings = ModerationSettings.DEFAULTS
                    .withXrayVeinminerModeEnabled(true);
            XrayWatchListener listener = new XrayWatchListener(servicesWith(settings, xray));
            Player player = playerWithId(MOD);
            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));

            listener.forget(MOD);
            listener.onBreak(eventFor(player, fullyEnclosedOre(Material.DIAMOND_ORE)));

            verify(xray, times(2)).mined(eq(MOD), eq("Mod"), any(MinedBlock.class));
        }
    }
}
