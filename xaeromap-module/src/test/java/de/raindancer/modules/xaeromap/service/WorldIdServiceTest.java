package de.raindancer.modules.xaeromap.service;

import de.raindancer.modules.xaeromap.XaeroMapSettings;
import de.raindancer.modules.xaeromap.model.XaeroWorldId;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** That a client is told which world it is in, on both channels, and only when that is switched on. */
class WorldIdServiceTest {

    private static final UUID OVERWORLD = UUID.randomUUID();
    private static final UUID NETHER = UUID.randomUUID();

    private final FakeWire wire = new FakeWire();
    private Player player;

    @BeforeEach
    void setUp() {
        player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        inWorld(OVERWORLD);
    }

    private void inWorld(UUID worldId) {
        World world = Mockito.mock(World.class);
        Mockito.when(world.getUID()).thenReturn(worldId);
        Mockito.when(player.getWorld()).thenReturn(world);
    }

    @Test
    @DisplayName("both mods are told, because a player may be running either")
    void bothChannelsAreUsed() {
        new WorldIdService(wire, XaeroMapSettings.DEFAULTS).send(player);

        assertThat(wire.all()).extracting(FakeWire.Sent::channel)
                .containsExactly(XaeroWorldId.MINIMAP_CHANNEL, XaeroWorldId.WORLDMAP_CHANNEL);
        assertThat(wire.all()).allMatch(sent ->
                java.util.Arrays.equals(sent.message(),
                        XaeroWorldId.packet(XaeroWorldId.of(OVERWORLD))));
    }

    @Test
    @DisplayName("two worlds are two ids, which is the whole point")
    void eachWorldGetsItsOwnId() {
        WorldIdService service = new WorldIdService(wire, XaeroMapSettings.DEFAULTS);

        service.send(player);
        byte[] above = wire.all().get(0).message();
        inWorld(NETHER);
        wire.clear();
        service.send(player);
        byte[] below = wire.all().get(0).message();

        assertThat(above)
                .as("one id for every world is the bug: the nether drawn over the overworld, in "
                        + "the client's own cache, permanently")
                .isNotEqualTo(below);
    }

    @Test
    @DisplayName("answering one channel answers only that channel")
    void oneChannelAtATime() {
        new WorldIdService(wire, XaeroMapSettings.DEFAULTS)
                .send(player, XaeroWorldId.WORLDMAP_CHANNEL);

        assertThat(wire.all()).hasSize(1);
        assertThat(wire.all().get(0).channel()).isEqualTo(XaeroWorldId.WORLDMAP_CHANNEL);
    }

    @Test
    @DisplayName("switched off is switched off, for a server whose other plugin already does this")
    void itCanBeTurnedOff() {
        WorldIdService service = new WorldIdService(wire,
                XaeroMapSettings.DEFAULTS.withWorldIds(false));

        service.send(player);
        service.send(player, XaeroWorldId.MINIMAP_CHANNEL);

        assertThat(wire.isEmpty())
                .as("two plugins sending different ids for the same world is worse than neither "
                        + "sending one")
                .isTrue();
    }

    @Test
    @DisplayName("a reload turns it back on without a restart")
    void reloadingTakesEffect() {
        WorldIdService service = new WorldIdService(wire,
                XaeroMapSettings.DEFAULTS.withWorldIds(false));
        service.settings(XaeroMapSettings.DEFAULTS);

        service.send(player);

        assertThat(wire.all()).hasSize(2);
    }
}
