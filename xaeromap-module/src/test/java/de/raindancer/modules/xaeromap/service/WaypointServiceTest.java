package de.raindancer.modules.xaeromap.service;

import de.raindancer.core.world.poi.Poi;
import de.raindancer.modules.xaeromap.XaeroMapSettings;
import de.raindancer.modules.xaeromap.model.Waypoint;
import de.raindancer.modules.xaeromap.model.XaeroShare;
import de.raindancer.modules.xaeromap.store.MapClients;
import de.raindancer.modules.xaeromap.store.PlaceLookup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That a player is offered their own places and nobody else's, in a form their client will take.
 *
 * <p>Two failures worth pinning above all: a share line sent to somebody without the mod is raw text in
 * their chat, and a warp offered to somebody who may not use it has handed them its coordinates —
 * which no later refusal takes back.
 */
class WaypointServiceTest {

    private static final String WORLD = "world";

    private final List<Poi> places = new ArrayList<>();
    private final MapClients clients = new MapClients();

    private Server server;
    private WaypointService waypoints;
    private Player player;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        World world = Mockito.mock(World.class);
        Mockito.when(world.getKey()).thenReturn(NamespacedKey.minecraft("overworld"));
        server = Mockito.mock(Server.class);
        Mockito.when(server.getWorld(WORLD)).thenReturn(world);

        PlaceLookup lookup = new PlaceLookup() {

            @Override
            public List<Poi> ofKind(String kind) {
                return places.stream().filter(place -> place.kind().equals(kind)).toList();
            }

            @Override
            public List<Poi> owned(UUID owner, String kind) {
                return places.stream()
                        .filter(place -> place.kind().equals(kind) && owner.equals(place.owner()))
                        .toList();
            }
        };
        waypoints = new WaypointService(() -> lookup, clients, server, XaeroMapSettings.DEFAULTS);

        playerId = UUID.randomUUID();
        player = Mockito.mock(Player.class);
        Mockito.when(player.getUniqueId()).thenReturn(playerId);
        clients.found(playerId);
    }

    private Poi place(String name, String kind, UUID owner) {
        return Poi.builder(name, WORLD, 100.7, 64.0, -200.2).kind(kind).owner(owner).build();
    }

    private List<String> sentLines() {
        ArgumentCaptor<Component> said = ArgumentCaptor.forClass(Component.class);
        Mockito.verify(player, Mockito.atLeast(0)).sendMessage(said.capture());
        return said.getAllValues().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }

    @Test
    @DisplayName("a player's own homes come back as waypoints")
    void homesAreOffered() {
        places.add(place("Base", WaypointService.HOMES, playerId));

        List<Waypoint> found = waypoints.homesOf(player);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).name()).isEqualTo("Base");
        assertThat(found.get(0).dimensionKey()).isEqualTo("minecraft:overworld");
        assertThat(found.get(0).colour()).isEqualTo(XaeroMapSettings.DEFAULTS.homeColour());
    }

    @Test
    @DisplayName("somebody else's home is never offered")
    void otherPeoplesHomesAreNot() {
        places.add(place("Their base", WaypointService.HOMES, UUID.randomUUID()));

        assertThat(waypoints.homesOf(player))
                .as("a waypoint is a set of coordinates; handing them over is the whole harm, and "
                        + "there is no taking it back afterwards")
                .isEmpty();
    }

    @Test
    @DisplayName("a warp anybody may use is offered")
    void publicWarpsAreOffered() {
        places.add(place("Spawn", WaypointService.WARPS, null));

        assertThat(waypoints.warpsFor(player)).hasSize(1);
    }

    @Test
    @DisplayName("a warp behind a permission is offered only to somebody who holds it")
    void restrictedWarpsFollowThePermission() {
        Poi staffRoom = Poi.builder("Staff room", WORLD, 0, 64, 0)
                .kind(WaypointService.WARPS)
                .tag("permission", "rainswarps.warp.staff")
                .build();
        places.add(staffRoom);

        Mockito.when(player.hasPermission("rainswarps.warp.staff")).thenReturn(false);
        assertThat(waypoints.warpsFor(player))
                .as("the name of a staff warp is the secret, and its coordinates more so")
                .isEmpty();

        Mockito.when(player.hasPermission("rainswarps.warp.staff")).thenReturn(true);
        assertThat(waypoints.warpsFor(player)).hasSize(1);
    }

    @Test
    @DisplayName("what is sent is the bare share line, and nothing else")
    void thelineIsSentUndecorated() {
        places.add(place("Sunset Hill", WaypointService.HOMES, playerId));

        int sent = waypoints.offer(player, waypoints.homesOf(player));

        assertThat(sent).isEqualTo(1);
        List<String> lines = sentLines();
        assertThat(lines).hasSize(1);
        assertThat(XaeroShare.looksValid(lines.get(0)))
                .as("the client matches the whole message, so a prefix or a colour makes it raw "
                        + "text in the player's chat instead of a button")
                .isTrue();
        assertThat(lines.get(0)).startsWith("xaero-waypoint:Sunset Hill:SH:");
    }

    @Test
    @DisplayName("nothing is sent to somebody without a map mod")
    void nothingGoesToAClientThatCannotReadIt() {
        places.add(place("Base", WaypointService.HOMES, playerId));
        clients.forget(playerId);

        int sent = waypoints.offer(player, waypoints.homesOf(player));

        assertThat(waypoints.canReceive(player)).isFalse();
        assertThat(sent).isZero();
        assertThat(sentLines())
                .as("without the mod the line is shown exactly as written, to somebody with no idea "
                        + "what it is")
                .isEmpty();
    }

    @Test
    @DisplayName("switched off means nothing is offered")
    void itCanBeTurnedOff() {
        waypoints.settings(XaeroMapSettings.DEFAULTS.withWaypoints(false));
        places.add(place("Base", WaypointService.HOMES, playerId));

        assertThat(waypoints.enabled()).isFalse();
        assertThat(waypoints.offer(player, waypoints.homesOf(player))).isZero();
    }

    @Test
    @DisplayName("a place in a world that is not loaded is left out rather than put on the wrong map")
    void unloadedWorldsAreSkipped() {
        Mockito.when(server.getWorld(WORLD)).thenReturn(null);
        places.add(place("Base", WaypointService.HOMES, playerId));

        assertThat(waypoints.homesOf(player))
                .as("guessing the dimension puts the waypoint on whichever map the player happens "
                        + "to be looking at")
                .isEmpty();
    }

    @Test
    @DisplayName("coordinates are whole blocks, rounded down, in both directions")
    void coordinatesAreWholeBlocks() {
        places.add(Poi.builder("Base", WORLD, 100.7, 64.9, -200.2)
                .kind(WaypointService.HOMES).owner(playerId).build());

        Waypoint waypoint = waypoints.homesOf(player).get(0);

        assertThat(waypoint.x()).isEqualTo(100);
        assertThat(waypoint.y()).isEqualTo(64);
        assertThat(waypoint.z())
                .as("truncating instead of flooring moves everything west and north of spawn a "
                        + "block towards it")
                .isEqualTo(-201);
    }

    @Test
    @DisplayName("a reload changes the colour without a restart")
    void reloadingTakesEffect() {
        places.add(place("Base", WaypointService.HOMES, playerId));
        waypoints.settings(XaeroMapSettings.DEFAULTS
                .withHomeColour(net.kyori.adventure.text.format.NamedTextColor.RED));

        assertThat(waypoints.homesOf(player).get(0).colour())
                .isEqualTo(net.kyori.adventure.text.format.NamedTextColor.RED);
    }

    @Test
    @DisplayName("a player with nothing gets nothing, rather than an empty offer")
    void nothingToOfferIsNothingSent() {
        assertThat(waypoints.homesOf(player)).isEmpty();
        assertThat(waypoints.offer(player, List.of())).isZero();
    }
}
