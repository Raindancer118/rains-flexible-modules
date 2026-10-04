package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.moderation.players.PlayerAdmin;
import de.raindancer.core.testkit.MemoryDataContainer;
import de.raindancer.core.testkit.TestInventories;
import de.raindancer.core.testkit.TestItems;
import de.raindancer.core.testkit.TestPlayers;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Somebody joining while a run is under way, by {@code late-join}: a latecomer who races gets exactly
 * what a racer got at the start; one who watches stands up again once it is over; one who raced
 * before keeps what they had; and with the rule OFF nothing changes at all.
 */
class SpeedrunLateJoinTest {

    @TempDir
    Path folder;

    private JavaPlugin plugin;
    private SettingsStore<SpeedrunSettings> settings;
    private MockedStatic<Bukkit> bukkit;
    private MockedStatic<HandlerList> handlers;
    private Messages messages;
    private PlayerAdmin admin;
    private World world;
    private Location spawn;
    private Player anna;
    private Player late;
    private final List<Player> online = new ArrayList<>();
    private final AtomicReference<Runnable> countdownDone = new AtomicReference<>();
    private final List<Location> placedAround = new ArrayList<>();
    private SpeedrunLobby lobby;
    private RecordingMode mode;

    /** A game that records what it was handed for a latecomer. */
    private static final class RecordingMode implements SpeedrunMode {
        final List<Player> lateJoined = new ArrayList<>();
        SpeedrunRun run;

        @Override
        public String id() {
            return "recording";
        }

        @Override
        public String label() {
            return "Recording";
        }

        @Override
        public Material icon() {
            return Material.TARGET;
        }

        @Override
        public java.util.Optional<String> refuseStart(SpeedrunSettings config, Set<UUID> participants) {
            return java.util.Optional.empty();
        }

        @Override
        public void onStart(SpeedrunRun run) {
            this.run = run;
        }

        @Override
        public void lateJoined(SpeedrunRun run, Player player) {
            assertThat(run).isSameAs(this.run);
            lateJoined.add(player);
        }
    }

    private Player player(String name) {
        Player player = TestPlayers.player(name);
        when(player.getPersistentDataContainer()).thenReturn(new MemoryDataContainer());
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(spawn.clone());
        when(player.getEnderChest()).thenReturn(TestInventories.chest(27));
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
        AtomicReference<GameMode> gameMode = new AtomicReference<>(GameMode.SURVIVAL);
        when(player.getGameMode()).thenAnswer(call -> gameMode.get());
        org.mockito.Mockito.doAnswer(call -> {
            gameMode.set(call.getArgument(0));
            return null;
        }).when(player).setGameMode(any(GameMode.class));
        bukkit.when(() -> Bukkit.getPlayer(player.getUniqueId())).thenReturn(player);
        online.add(player);
        return player;
    }

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        settings = new SettingsStore<>(SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                folder.resolve("speedrun.yml"));
        settings.load();
        settings.set("world-name", "world");
        settings.set("late-join", "RACE");
        bukkit = mockStatic(Bukkit.class);
        handlers = mockStatic(HandlerList.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        spawn = new Location(world, 8, 70, 8);
        when(world.getSpawnLocation()).thenReturn(spawn);
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        bukkit.when(Bukkit::getOnlinePlayers).thenAnswer(call -> List.copyOf(online));
        messages = mock(Messages.class);
        admin = mock(PlayerAdmin.class);
        mode = new RecordingMode();
        SpeedrunModes.clear();
        SpeedrunModes.offer(mode);
        anna = player("Anna");
        late = player("Late");
        lobby = new SpeedrunLobby(plugin, settings, (participants, onComplete) -> countdownDone.set(onComplete),
                messages, null, new SpeedrunPreparation(plugin, admin));
        lobby.placeLatecomersWith(around -> {
            placedAround.add(around);
            return CompletableFuture.completedFuture(around.clone().add(0.5, 0, 0.5));
        });
    }

    @AfterEach
    void tearDown() {
        handlers.close();
        bukkit.close();
        SpeedrunModes.clear();
    }

    private void running() {
        assertThat(lobby.beginCountdown(Set.of(anna.getUniqueId()))).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);
        countdownDone.get().run();
        assertThat(lobby.state()).isEqualTo(SpeedrunLobbyState.RUNNING);
    }

    @Nested
    @DisplayName("late-join RACE")
    class Race {

        @Test
        @DisplayName("a latecomer becomes a racer from now: on the roster, the clock and the timeline")
        void joinsTheRun() {
            running();

            assertThat(lobby.arrive(late)).isEqualTo(SpeedrunLatecomers.Arrival.RACE);

            SpeedrunSession session = lobby.session().orElseThrow();
            assertThat(session.participants()).contains(late.getUniqueId());
            assertThat(session.timeline().entries()).anySatisfy(entry -> {
                assertThat(entry.kind()).isEqualTo(SpeedrunTimeline.Kind.JOINED);
                assertThat(entry.who()).isEqualTo(late.getUniqueId());
            });
        }

        @Test
        @DisplayName("…with the clean slate everybody had at the start, and the practice kit")
        void cleanSlateAndKit() {
            settings.set("practice-kit", "EYES_OF_ENDER");
            running();
            late.getInventory().setItem(0, TestItems.of(Material.DIAMOND_BLOCK, 64));

            lobby.arrive(late);

            verify(admin).heal(late.getUniqueId());
            verify(admin).feed(late.getUniqueId());
            assertThat(TestInventories.stacksIn(late.getInventory())).extracting(stack -> stack.getType())
                    .containsExactly(Material.ENDER_EYE);
        }

        @Test
        @DisplayName("…put on a safe spot by the start line — or the world's spawn — which becomes their respawn point")
        void placedSafely() {
            running();

            lobby.arrive(late);

            assertThat(placedAround).containsExactly(spawn);
            Location placed = spawn.clone().add(0.5, 0, 0.5);
            verify(late).teleportAsync(placed);
            verify(late).setRespawnLocation(placed, true);
        }

        @Test
        @DisplayName("…with the start line set, near the start line")
        void nearTheStartLine() {
            settings.set("start-point-set", "true");
            settings.set("start-x", "100");
            settings.set("start-y", "64");
            settings.set("start-z", "-20");
            running();

            lobby.arrive(late);

            assertThat(placedAround).singleElement().satisfies(around -> {
                assertThat(around.getX()).isEqualTo(100);
                assertThat(around.getZ()).isEqualTo(-20);
            });
        }

        @Test
        @DisplayName("…playing, not watching, and handed the game's own items by the game")
        void survivalAndTheModesItems() {
            settings.set("game-mode", "recording");
            running();
            late.setGameMode(GameMode.SPECTATOR);

            lobby.arrive(late);

            assertThat(late.getGameMode()).isEqualTo(GameMode.SURVIVAL);
            assertThat(mode.lateJoined).containsExactly(late);
        }

        @Test
        @DisplayName("…told so, and everybody racing is told who joined")
        void told() {
            running();

            lobby.arrive(late);

            verify(messages).send(eq(late), eq("speedrun.late-join.racing"), any(Object[].class));
            verify(messages).send(eq(anna), eq("speedrun.late-join.joined"), any(Object[].class));
        }

        @Test
        @DisplayName("a run paused because everybody left picks up again with its new racer")
        void resumesAPausedRun() {
            running();
            lobby.session().orElseThrow().pauseForEmptyRoster();

            lobby.arrive(late);

            assertThat(lobby.session().orElseThrow().state()).isEqualTo(SpeedrunState.RUNNING);
        }

        @Test
        @DisplayName("joining in the countdown, they race from the start like everybody else — no late join at all")
        void inTheCountdown() {
            assertThat(lobby.beginCountdown(Set.of(anna.getUniqueId()))).isEqualTo(SpeedrunLobby.StartOutcome.STARTED);

            assertThat(lobby.arrive(late)).isEqualTo(SpeedrunLatecomers.Arrival.NEXT_START);
            countdownDone.get().run();

            SpeedrunSession session = lobby.session().orElseThrow();
            assertThat(session.participants()).containsExactlyInAnyOrder(anna.getUniqueId(), late.getUniqueId());
            assertThat(session.timeline().entries()).noneMatch(entry -> entry.kind() == SpeedrunTimeline.Kind.JOINED);
        }

        @Test
        @DisplayName("somebody who said they are not racing only watches")
        void notRacingWatches() {
            running();
            lobby.toggleSpectator(late.getUniqueId());

            assertThat(lobby.arrive(late)).isEqualTo(SpeedrunLatecomers.Arrival.WATCH);
            assertThat(lobby.session().orElseThrow().participants()).doesNotContain(late.getUniqueId());
        }

        @Test
        @DisplayName("a racer coming back is no latecomer: nothing of theirs is cleared, nothing handed twice")
        void returning() {
            running();
            anna.getInventory().setItem(0, TestItems.of(Material.DIAMOND, 3));

            assertThat(lobby.arrive(anna)).isEqualTo(SpeedrunLatecomers.Arrival.RETURNING);

            assertThat(TestInventories.stacksIn(anna.getInventory())).hasSize(1);
            verify(anna, never()).teleportAsync(any(Location.class));
        }
    }

    @Nested
    @DisplayName("late-join SPECTATE")
    class Spectate {

        @BeforeEach
        void watching() {
            settings.set("late-join", "SPECTATE");
        }

        @Test
        @DisplayName("a latecomer watches in spectator mode from the start line, and is no racer")
        void watches() {
            running();

            assertThat(lobby.arrive(late)).isEqualTo(SpeedrunLatecomers.Arrival.WATCH);

            assertThat(late.getGameMode()).isEqualTo(GameMode.SPECTATOR);
            verify(late).teleportAsync(spawn);
            assertThat(lobby.session().orElseThrow().participants()).doesNotContain(late.getUniqueId());
            verify(messages).send(eq(late), eq("speedrun.late-join.watching"), any(Object[].class));
        }

        @Test
        @DisplayName("once the run is over they stand up again")
        void standUpAfterwards() {
            running();
            lobby.arrive(late);

            lobby.session().orElseThrow().finish("advancement:x");

            assertThat(late.getGameMode()).isEqualTo(GameMode.SURVIVAL);
            assertThat(SpeedrunLatecomers.WATCHING.isOn(late)).isFalse();
        }

        @Test
        @DisplayName("…and if they were away when it ended — a restart in between — the next time they join")
        void standUpOnTheNextJoin() {
            running();
            lobby.arrive(late);
            online.remove(late);
            lobby.session().orElseThrow().finish("advancement:x");

            online.add(late);
            assertThat(lobby.arrive(late)).isEqualTo(SpeedrunLatecomers.Arrival.NO_RUN);

            assertThat(late.getGameMode()).isEqualTo(GameMode.SURVIVAL);
        }
    }

    @Nested
    @DisplayName("late-join OFF — the default")
    class Off {

        @Test
        @DisplayName("nothing changes: they look on, keep their things, and are no racer")
        void nothingChanges() {
            settings.set("late-join", "OFF");
            running();
            late.getInventory().setItem(0, TestItems.of(Material.DIAMOND, 3));

            assertThat(lobby.arrive(late)).isEqualTo(SpeedrunLatecomers.Arrival.LOOK_ON);

            assertThat(lobby.session().orElseThrow().participants()).doesNotContain(late.getUniqueId());
            assertThat(TestInventories.stacksIn(late.getInventory())).hasSize(1);
            assertThat(late.getGameMode()).isEqualTo(GameMode.SURVIVAL);
            verify(late, never()).teleportAsync(any(Location.class));
        }
    }

    @Nested
    @DisplayName("what a late racer's result counts for")
    class Ranking {

        private SpeedrunRunRecord withLatecomer(UUID early, UUID latecomer) {
            return new SpeedrunRunRecord("late-run", SpeedrunHistoryTest.DRAGON, 1_000, Duration.ofMinutes(20),
                    "advancement:x", true, 1L, Map.of(early, "Early", latecomer, "Late"),
                    List.of(new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.JOINED, Duration.ofMinutes(7), latecomer, ""),
                            new SpeedrunTimeline.Entry(SpeedrunTimeline.Kind.FINISH, Duration.ofMinutes(20), null, "advancement:x")),
                    Map.of());
        }

        @Test
        @DisplayName("the run ranks for those who ran it from the start — never as a best of the latecomer's")
        void neverABestOfTheirs() {
            UUID early = anna.getUniqueId();
            UUID latecomer = late.getUniqueId();
            SpeedrunHistory history = Histories.inMemory();
            SpeedrunRunRecord run = withLatecomer(early, latecomer);

            history.add(run);

            assertThat(run.joinedLate(latecomer)).isTrue();
            assertThat(run.joinedLate(early)).isFalse();
            assertThat(history.personalBest(early, SpeedrunHistoryTest.DRAGON)).contains(run);
            assertThat(history.personalBest(latecomer, SpeedrunHistoryTest.DRAGON)).isEmpty();
            assertThat(history.runs().byId("late-run").orElseThrow().players()).containsOnlyKeys(early);
            assertThat(history.runsOf(latecomer)).as("still in their history").containsExactly(run);
            assertThat(history.byId("late-run").orElseThrow()).as("read back whole").isEqualTo(run);
        }
    }
}
