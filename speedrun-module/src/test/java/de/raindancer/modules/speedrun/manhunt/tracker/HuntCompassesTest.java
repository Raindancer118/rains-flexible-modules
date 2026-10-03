package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The three compasses as one: what a side change and a login do to all of them together. */
@DisplayName("the hunt's compasses, together")
class HuntCompassesTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    private final TrackerCompassService tracker = mock(TrackerCompassService.class);
    private final TeamCompassService team = mock(TeamCompassService.class);
    private final StructureCompassService structures = mock(StructureCompassService.class);
    private final AtomicReference<Hunt> live = new AtomicReference<>();
    private final Plugin plugin = mock(Plugin.class);
    private HuntCompasses compasses;
    private Player player;

    @BeforeEach
    void setUp() {
        compasses = new HuntCompasses(plugin, () -> Optional.ofNullable(live.get()), tracker, team, structures);
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(RUNNER);
    }

    @Test
    @DisplayName("a side change refits every compass, on the player's own thread")
    void refitIsOnTheirThread() {
        when(plugin.isEnabled()).thenReturn(true);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        AtomicReference<Consumer<Object>> queued = new AtomicReference<>();
        when(scheduler.run(any(), any(), any())).thenAnswer(call -> {
            queued.set(call.getArgument(1));
            return null;
        });
        when(player.getScheduler()).thenReturn(scheduler);
        Hunt hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));

        compasses.refit(hunt, player);
        verify(tracker, never()).fit(any(), any());

        queued.get().accept(null);
        verify(tracker).fit(hunt, player);
        verify(team).fit(hunt, player);
        verify(structures).fit(hunt, player);
        // A side change: the old pick named somebody on the side they just left.
        verify(tracker).forget(RUNNER);
        verify(team).forget(RUNNER);
    }

    @Test
    @DisplayName("a setting changed mid-hunt refits everybody online at once, picks kept")
    void settingsChangedMidHunt() {
        org.bukkit.Server server = mock(org.bukkit.Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPlayer(RUNNER)).thenReturn(player);
        Hunt hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
        live.set(hunt);

        compasses.settingsChanged();

        verify(tracker).fit(hunt, player);
        verify(team).fit(hunt, player);
        verify(structures).fit(hunt, player);
        verify(tracker, never()).forget(any());
    }

    @Test
    @DisplayName("a setting changed between hunts touches nobody")
    void settingsChangedInTheLobby() {
        compasses.settingsChanged();

        verify(tracker, never()).fit(any(), any());
    }

    @Test
    @DisplayName("logging in during a hunt hands over what is owed and takes what is not")
    void joinDuringAHunt() {
        Hunt hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
        live.set(hunt);

        compasses.onJoin(new PlayerJoinEvent(player, (Component) null));

        verify(tracker).fit(hunt, player);
        verify(team).fit(hunt, player);
        verify(structures).fit(hunt, player);
    }

    @Test
    @DisplayName("logging in after a hunt ended without you takes back the compasses nobody can drop")
    void joinAfterTheHunt() {
        compasses.onJoin(new PlayerJoinEvent(player, (Component) null));

        verify(tracker).takeBack(player);
        verify(team).takeBack(player);
        // Not bound, so it can be thrown away — and a resumed hunt still recognises a chosen one.
        verify(structures, never()).takeBack(player);
    }
}
