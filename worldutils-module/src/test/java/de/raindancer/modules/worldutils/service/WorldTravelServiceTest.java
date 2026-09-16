package de.raindancer.modules.worldutils.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.manage.WorldEntryRules;
import de.raindancer.core.world.safety.Safety;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.worldutils.WorldUtilsSettings;
import de.raindancer.modules.worldutils.model.Dimension;
import de.raindancer.modules.worldutils.store.LastPositions;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Where /w and /dim aim, and that whatever locks a world is asked first. Safe arrival is switched off
 * here so the exact aim reaches Travel; the ground search itself is Core's and tested there.
 */
class WorldTravelServiceTest {

    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final Travel travel = mock(Travel.class);
    private final Safety safety = mock(Safety.class);
    private final WorldEntryRules entry = new WorldEntryRules();
    private final Messages messages = mock(Messages.class);
    private final LastPositions positions = mock(LastPositions.class);
    private final CommandSender admin = mock(CommandSender.class);

    private WorldTravelService service;
    private Player player;
    private World farm;

    private static World world(String name, World.Environment environment, double scale) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        when(world.getEnvironment()).thenReturn(environment);
        when(world.getCoordinateScale()).thenReturn(scale);
        when(world.getMinHeight()).thenReturn(environment == World.Environment.NORMAL ? -64 : 0);
        when(world.getMaxHeight()).thenReturn(environment == World.Environment.NORMAL ? 320 : 256);
        when(world.getLogicalHeight()).thenReturn(environment == World.Environment.NETHER ? 128 : 384);
        WorldBorder border = mock(WorldBorder.class);
        when(border.getCenter()).thenReturn(new Location(world, 0, 0, 0));
        when(border.getSize()).thenReturn(6.0E7);
        when(world.getWorldBorder()).thenReturn(border);
        return world;
    }

    @BeforeEach
    void setUp() {
        service = new WorldTravelService(plugin, server, travel, safety, entry, messages, positions,
                WorldUtilsSettings.DEFAULTS.withSafeArrival(false));
        farm = world("farm", World.Environment.NORMAL, 1);
        when(server.getWorld("farm")).thenReturn(farm);
        player = mock(Player.class);
        when(player.getName()).thenReturn("Alex");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Consumer<Object>>getArgument(1).accept(null);
            return null;
        });
        when(player.getScheduler()).thenReturn(scheduler);
    }

    private Location sentTo() {
        ArgumentCaptor<Location> where = ArgumentCaptor.forClass(Location.class);
        verify(travel).go(any(Player.class), where.capture(), any(Trip.class), any());
        return where.getValue();
    }

    @Test
    @DisplayName("/w goes back to where they last stood in that world")
    void backToTheLastPosition() {
        World hub = world("hub", World.Environment.NORMAL, 1);
        when(player.getWorld()).thenReturn(hub);
        when(positions.in(player.getUniqueId(), "farm")).thenReturn(Optional.of(new Location(farm, 100, 70, -50)));

        service.toWorld(admin, player, farm);

        Location target = sentTo();
        assertThat(target.getWorld()).isSameAs(farm);
        assertThat(target.getBlockX()).isEqualTo(100);
        assertThat(target.getBlockZ()).isEqualTo(-50);
    }

    @Test
    @DisplayName("/w with nothing remembered, or remembering switched off, goes to the world's spawn")
    void spawnOtherwise() {
        World hub = world("hub", World.Environment.NORMAL, 1);
        when(player.getWorld()).thenReturn(hub);
        when(positions.in(any(), any())).thenReturn(Optional.empty());
        when(farm.getSpawnLocation()).thenReturn(new Location(farm, 5, 64, 5));

        service.toWorld(admin, player, farm);

        assertThat(sentTo().getBlockX()).isEqualTo(5);
    }

    @Test
    @DisplayName("a world something has locked is refused, and the refusal is its own words")
    void lockedWorldsAreRefused() {
        World hub = world("hub", World.Environment.NORMAL, 1);
        when(player.getWorld()).thenReturn(hub);
        entry.register("gate", (who, where) -> Optional.of(Component.text("The End is closed")));

        service.toWorld(admin, player, farm);

        verify(travel, never()).go(any(), any(), any(), any());
        verify(admin).sendMessage(Component.text("The End is closed"));
        verify(player).sendMessage(Component.text("The End is closed"));
    }

    @Test
    @DisplayName("/dim nether from the family's overworld lands at an eighth of the coordinates, in that family's nether")
    void intoTheFamilysNether() {
        World farmNether = world("farm_nether", World.Environment.NETHER, 8);
        when(server.getWorld("farm_nether")).thenReturn(farmNether);
        when(player.getWorld()).thenReturn(farm);
        when(player.getLocation()).thenReturn(new Location(farm, 800, 70, 1600));

        service.toDimension(admin, player, Dimension.NETHER);

        Location target = sentTo();
        assertThat(target.getWorld()).isSameAs(farmNether);
        assertThat(target.getBlockX()).isEqualTo(100);
        assertThat(target.getBlockZ()).isEqualTo(200);
    }

    @Test
    @DisplayName("a Nether landing on top of the bedrock roof is refused, not taken")
    void neverTheRoof() {
        service = new WorldTravelService(plugin, server, travel, safety, entry, messages, positions,
                WorldUtilsSettings.DEFAULTS);
        World farmNether = world("farm_nether", World.Environment.NETHER, 8);
        when(farmNether.getLogicalHeight()).thenReturn(128);
        when(server.getWorld("farm_nether")).thenReturn(farmNether);
        when(player.getWorld()).thenReturn(farm);
        when(player.getLocation()).thenReturn(new Location(farm, 0, 200, 0));
        when(safety.findSafe(any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(
                java.util.concurrent.CompletableFuture.completedFuture(
                        Optional.of(new de.raindancer.core.world.safety.Spot("farm_nether", 0, 128, 0))));

        service.toDimension(admin, player, Dimension.NETHER);

        verify(travel, never()).go(any(), any(), any(), any());
        verify(messages).send(admin, "worldutils.nowhere-safe", "player", "Alex", "world", "farm_nether");
    }

    @Test
    @DisplayName("/dim into a dimension this world has none of says so rather than using the server's")
    void noSuchDimension() {
        when(player.getWorld()).thenReturn(farm);

        service.toDimension(admin, player, Dimension.END);

        verify(travel, never()).go(any(), any(), any(), any());
        verify(messages).send(admin, "worldutils.no-such-dimension", "dimension", "the End", "world", "farm_the_end");
    }
}
