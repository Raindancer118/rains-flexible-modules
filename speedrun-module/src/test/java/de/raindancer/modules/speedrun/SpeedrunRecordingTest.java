package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.ui.effect.Cues;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.manage.WorldSeed;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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
 * A run going into the history: what category it is filed under, what it keeps, what everybody who
 * raced is told, and the seed the next world is made from.
 */
class SpeedrunRecordingTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    @TempDir
    Path folder;

    private JavaPlugin plugin;
    private SettingsStore<SpeedrunSettings> settings;
    private MockedStatic<Bukkit> bukkit;
    private World world;
    private Player alice;
    private Messages messages;
    private Effects effects;
    private SpeedrunHistory history;

    @BeforeEach
    void setUp() {
        plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(plugin.isEnabled()).thenReturn(true);
        settings = new SettingsStore<>(SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                folder.resolve("speedrun.yml"));
        settings.load();
        settings.set("world-name", "world");
        settings.set("restart-when-run-ends", "false");
        bukkit = mockStatic(Bukkit.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(world.getSeed()).thenReturn(1234L);
        when(world.getPlayers()).thenReturn(List.of());
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);
        bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
        alice = mock(Player.class, Mockito.RETURNS_DEEP_STUBS);
        when(alice.getUniqueId()).thenReturn(ALICE);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, Consumer.class).accept(null);
            return null;
        });
        when(alice.getScheduler()).thenReturn(scheduler);
        bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);
        OfflinePlayer offline = mock(OfflinePlayer.class);
        when(offline.getName()).thenReturn("Alice");
        bukkit.when(() -> Bukkit.getOfflinePlayer(ALICE)).thenReturn(offline);
        messages = mock(Messages.class);
        when(messages.prefixed(anyString(), any(Object[].class))).thenReturn(Component.text("line"));
        when(messages.get(anyString(), any(Object[].class))).thenReturn(Component.text("title"));
        effects = mock(Effects.class);
        history = Histories.inMemory(folder);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
    }

    private SpeedrunLobby equipped() {
        SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
        lobby.equip(new SpeedrunToolkit(plugin, null, null, messages, null, null, null, effects, history, null, null));
        return lobby;
    }

    @Test
    @DisplayName("a finished run is kept: its category, seed, racers, splits and the finish split")
    void kept() {
        SpeedrunLobby lobby = equipped();
        lobby.start(Set.of(ALICE));
        lobby.splits().orElseThrow().reach("enter-nether", ALICE);

        lobby.session().orElseThrow().finish("advancement:minecraft:end/kill_dragon");

        SpeedrunRunRecord run = history.all().getFirst();
        assertThat(run.category()).isEqualTo(new SpeedrunCategory("minecraft:end/kill_dragon",
                SpeedrunSeedType.RANDOM, "", ""));
        assertThat(run.seed()).isEqualTo(1234L);
        assertThat(run.participants()).containsEntry(ALICE, "Alice");
        assertThat(run.completed()).isTrue();
        assertThat(run.ranked()).isTrue();
        assertThat(run.splits()).extracting(SpeedrunTimeline.Entry::detail).containsExactly("enter-nether", "finish");
        assertThat(lobby.lastRun()).contains(run);
    }

    @Test
    @DisplayName("the first ranked run of a category is a record, and the racer is told")
    void firstIsARecord() {
        SpeedrunLobby lobby = equipped();
        lobby.start(Set.of(ALICE));

        lobby.session().orElseThrow().finish("advancement:minecraft:end/kill_dragon");

        verify(messages).send(eq(alice), eq("speedrun.result.record"), any(Object[].class));
    }

    @Test
    @DisplayName("a run that did not reach the goal is kept, not ranked, and says why")
    void unfinishedIsNotRanked() {
        bukkit.when(Bukkit::getGlobalRegionScheduler)
                .thenReturn(mock(io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler.class));
        SpeedrunLobby lobby = equipped();
        lobby.start(Set.of(ALICE));

        lobby.forceReset();

        assertThat(history.all()).singleElement().satisfies(run -> {
            assertThat(run.completed()).isFalse();
            assertThat(run.outcome()).isEqualTo("admin-reset");
        });
        verify(messages).send(eq(alice), eq("speedrun.result.not-ranked"), eq("reason"), eq("the goal was not reached"));
    }

    @Test
    @DisplayName("a resumed run is flagged in the history")
    void resumedIsFlagged() {
        SpeedrunLobby lobby = equipped();
        lobby.resume(Set.of(ALICE), Duration.ofMinutes(30));

        lobby.session().orElseThrow().finish("advancement:minecraft:end/kill_dragon");

        SpeedrunRunRecord run = history.all().getFirst();
        assertThat(run.resumed()).isTrue();
        assertThat(run.ranked()).isFalse();
    }

    @Test
    @DisplayName("a practice kit files the run as practice, and a configured seed as a set seed")
    void categoryFromSettings() {
        settings.set("practice-kit", "EYES_OF_ENDER");
        settings.set("seed", "1234");
        SpeedrunLobby lobby = equipped();
        lobby.start(Set.of(ALICE));

        assertThat(lobby.splits().orElseThrow().category().orElseThrow())
                .isEqualTo(new SpeedrunCategory("minecraft:end/kill_dragon", SpeedrunSeedType.SET, "", "EYES_OF_ENDER"));
    }

    @Test
    @DisplayName("a split is said to every racer; a gold one also gets a title and the earned sound")
    void splitsAreAnnounced() {
        history.add(SpeedrunHistoryTest.run(new SpeedrunCategory("minecraft:end/kill_dragon",
                SpeedrunSeedType.RANDOM, "", ""), Duration.ofHours(1), Duration.ofHours(2), ALICE));
        SpeedrunLobby lobby = equipped();
        lobby.start(Set.of(ALICE));

        lobby.splits().orElseThrow().reach("enter-nether", ALICE);

        verify(messages).prefixed(eq("speedrun.split.reached-by"), any(Object[].class));
        verify(alice).showTitle(any(Title.class));
        verify(effects).play(ALICE, Cues.EARNED);
    }

    @Test
    @DisplayName("with announcements off, a split is silent in chat")
    void announcementsOff() {
        settings.set("split-announcements", "false");
        SpeedrunLobby lobby = equipped();
        lobby.start(Set.of(ALICE));

        lobby.splits().orElseThrow().reach("enter-nether", ALICE);

        verify(messages, never()).prefixed(eq("speedrun.split.reached-by"), any(Object[].class));
    }

    @Test
    @DisplayName("'same seed again' remakes this map once, then the seed setting rules again")
    void sameSeedOnce() {
        SpeedrunLobby lobby = equipped();

        lobby.replaySeedNextReset();

        assertThat(lobby.replayingSeed()).isTrue();
        assertThat(lobby.nextSeed()).isEqualTo(WorldSeed.same());
        assertThat(lobby.nextSeed()).isEqualTo(WorldSeed.random());
        settings.set("seed-mode", "FIXED");
        settings.set("seed", "77");
        assertThat(lobby.nextSeed()).isEqualTo(WorldSeed.fixed(77));
    }

    @Test
    @DisplayName("a game mode's own milestone, split and HUD lines go through the run")
    void modeApi() {
        SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
        session.start();
        SpeedrunRun run = new SpeedrunRun(plugin, session, SpeedrunWorlds.around("world"));
        run.declareMilestone("first-catch", "First catch", Material.IRON_SWORD);
        run.hudLines(viewer -> List.of(Component.text("Runners left: 1")));

        assertThat(run.split("first-catch", ALICE)).isTrue();
        assertThat(session.timeline().splitAt("first-catch")).isPresent();
        assertThat(run.splits().hudLinesFor(ALICE)).containsExactly(Component.text("Runners left: 1"));
    }

    @Test
    @DisplayName("a mode's own end counts as reaching the goal when the mode says so")
    void modeDecidesCompletion() {
        SpeedrunMode mode = new SpeedrunMode() {
            public String id() { return "hunt"; }
            public String label() { return "Hunt"; }
            public Material icon() { return Material.STONE; }
            public java.util.Optional<String> refuseStart(SpeedrunSettings c, Set<UUID> p) { return java.util.Optional.empty(); }
            public boolean endsItself() { return true; }
            public void onStart(SpeedrunRun run) { }
            public boolean leaderboardEligible(SpeedrunOutcome outcome) { return outcome.reason().equals("runners-won"); }
        };
        SpeedrunModes.offer(mode);
        try {
            settings.set("game-mode", "hunt");
            SpeedrunLobby lobby = equipped();
            lobby.start(Set.of(ALICE));

            lobby.session().orElseThrow().finish("runners-won");

            assertThat(history.all().getFirst().completed()).isTrue();
            assertThat(history.all().getFirst().category().mode()).isEqualTo("hunt");
        } finally {
            SpeedrunModes.clear();
        }
    }
}
