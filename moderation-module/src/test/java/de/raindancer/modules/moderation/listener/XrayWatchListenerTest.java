package de.raindancer.modules.moderation.listener;

import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.service.XrayEvidenceService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The listener only hands digging over; what a dig reveals is the evidence service's business. */
class XrayWatchListenerTest {

    private static final World WORLD = mock(World.class);

    static {
        when(WORLD.getUID()).thenReturn(UUID.randomUUID());
    }

    private static Block block(Material material, int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.getLocation()).thenReturn(new Location(WORLD, x, y, z));
        when(block.getWorld()).thenReturn(WORLD);
        return block;
    }

    private static Player player(boolean bypass) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(SuspiciousCommandListener.BYPASS)).thenReturn(bypass);
        return player;
    }

    private static XrayWatchListener listener(XrayEvidenceService evidence) {
        ModerationServices services = mock(ModerationServices.class);
        when(services.config()).thenReturn(ModerationSettings.DEFAULTS);
        when(services.xrayDetection()).thenReturn(evidence);
        return new XrayWatchListener(services);
    }

    @Test
    @DisplayName("every block a player digs goes to the evidence")
    void digsAreHandedOver() {
        XrayEvidenceService evidence = mock(XrayEvidenceService.class);
        Player miner = player(false);
        Block diamond = block(Material.DIAMOND_ORE, 1, 2, 3);

        listener(evidence).onBreak(new BlockBreakEvent(diamond, miner));

        verify(evidence, times(1)).dug(miner, diamond);
    }

    @Test
    @DisplayName("ore somebody placed and broke again is not mining")
    void placedOreIsSkipped() {
        XrayEvidenceService evidence = mock(XrayEvidenceService.class);
        XrayWatchListener listener = listener(evidence);
        Player builder = player(false);
        Block diamond = block(Material.DIAMOND_ORE, 4, 5, 6);
        BlockPlaceEvent placed = mock(BlockPlaceEvent.class);
        when(placed.getBlock()).thenReturn(diamond);

        listener.onPlace(placed);
        listener.onBreak(new BlockBreakEvent(diamond, builder));

        verify(evidence, never()).dug(builder, diamond);
    }

    @Test
    @DisplayName("staff with the bypass are never watched")
    void bypass() {
        XrayEvidenceService evidence = mock(XrayEvidenceService.class);
        Player staff = player(true);
        Block stone = block(Material.STONE, 7, 8, 9);

        listener(evidence).onBreak(new BlockBreakEvent(stone, staff));

        verify(evidence, never()).dug(staff, stone);
    }
}
