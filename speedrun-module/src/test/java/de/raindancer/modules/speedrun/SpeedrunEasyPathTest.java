package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.ui.actionbar.ActionBarSink;
import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The easy path: the pre-flight check, /speedrun's words, the setup questions, the HUD and its choices. */
class SpeedrunEasyPathTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

    @TempDir
    Path folder;

    private JavaPlugin plugin;
    private SettingsStore<SpeedrunSettings> settings;
    private MockedStatic<Bukkit> bukkit;

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
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getWorld(anyString())).thenReturn(null);
        SpeedrunModes.clear();
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        SpeedrunModes.clear();
    }

    private World loaded(String name) {
        World world = mock(World.class);
        when(world.getName()).thenReturn(name);
        bukkit.when(() -> Bukkit.getWorld(name)).thenReturn(world);
        return world;
    }

    @Nested
    @DisplayName("the pre-flight check")
    class Preflight {

        private SpeedrunPreflight.Check check(SpeedrunPreflight preflight, String id) {
            return preflight.checks().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
        }

        @Test
        @DisplayName("a lobby with its worlds, a goal and a racer is clear")
        void clear() {
            loaded("world");
            loaded("world_nether");
            loaded("world_the_end");
            bukkit.when(() -> Bukkit.getAdvancement(any())).thenReturn(mock(org.bukkit.advancement.Advancement.class));

            SpeedrunPreflight preflight = SpeedrunPreflight.of(new SpeedrunLobby(plugin, settings), Set.of(ALICE));

            assertThat(preflight.clear()).isTrue();
            assertThat(preflight.problems()).isEmpty();
        }

        @Test
        @DisplayName("every way a start is refused is a red check with its one-click fix")
        void everyProblemHasAFix() {
            settings.set("advancement-key", "");
            settings.set("game-mode", "manhunt");

            SpeedrunPreflight preflight = SpeedrunPreflight.of(new SpeedrunLobby(plugin, settings), Set.of());

            assertThat(preflight.clear()).isFalse();
            assertThat(check(preflight, "world").fix()).isEqualTo(SpeedrunPreflight.Fix.CREATE_WORLDS);
            assertThat(check(preflight, "mode").fix()).isEqualTo(SpeedrunPreflight.Fix.PLAIN_RACE);
            assertThat(check(preflight, "goal").fix()).isEqualTo(SpeedrunPreflight.Fix.DRAGON_GOAL);
            assertThat(check(preflight, "racers").fix()).isEqualTo(SpeedrunPreflight.Fix.BRING_EVERYBODY);
            assertThat(preflight.checks().stream().filter(SpeedrunPreflight.Check::stopsTheStart))
                    .allMatch(c -> c.fix() != SpeedrunPreflight.Fix.NONE);
        }

        @Test
        @DisplayName("a practice kit and a seed mode without a seed are warnings, not refusals")
        void warnings() {
            loaded("world");
            settings.set("practice-kit", "EYES_OF_ENDER");
            settings.set("seed-mode", "FIXED");

            SpeedrunPreflight preflight = SpeedrunPreflight.of(new SpeedrunLobby(plugin, settings), Set.of(ALICE));

            assertThat(check(preflight, "practice").blocking()).isFalse();
            assertThat(check(preflight, "practice").fix()).isEqualTo(SpeedrunPreflight.Fix.NO_KIT);
            assertThat(check(preflight, "seed").fix()).isEqualTo(SpeedrunPreflight.Fix.RANDOM_SEED);
        }

        @Test
        @DisplayName("the fixes do what their buttons say")
        void fixesWork() {
            settings.set("advancement-key", "");
            settings.set("game-mode", "manhunt");
            settings.set("practice-kit", "EYES_OF_ENDER");
            settings.set("seed-mode", "POOL");
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            SpeedrunActions actions = new SpeedrunActions(lobby);
            Player admin = mock(Player.class);

            actions.apply(SpeedrunPreflight.Fix.DRAGON_GOAL, admin, null);
            actions.apply(SpeedrunPreflight.Fix.PLAIN_RACE, admin, null);
            actions.apply(SpeedrunPreflight.Fix.NO_KIT, admin, null);
            actions.apply(SpeedrunPreflight.Fix.RANDOM_SEED, admin, null);

            SpeedrunSettings now = lobby.config();
            assertThat(now.isDragonKillGoal()).isTrue();
            assertThat(now.hasGameMode()).isFalse();
            assertThat(now.kit()).isEqualTo(SpeedrunPracticeKit.NONE);
            assertThat(now.seedMode()).isEqualTo(SpeedrunSeedMode.RANDOM);
        }
    }

    @Nested
    @DisplayName("/speedrun")
    class Command {

        private SpeedrunJoinCommand command;
        private Messages messages;
        private SpeedrunLobby lobby;

        @BeforeEach
        void build() {
            lobby = new SpeedrunLobby(plugin, settings);
            messages = mock(Messages.class);
            when(messages.get(anyString(), any(Object[].class))).thenReturn(Component.text("line"));
            command = new SpeedrunJoinCommand(() -> new SpeedrunAdminServices(lobby, messages));
        }

        private CommandSourceStack from(CommandSender sender) {
            CommandSourceStack source = mock(CommandSourceStack.class);
            when(source.getSender()).thenReturn(sender);
            return source;
        }

        @Test
        @DisplayName("tab completion offers every word — and the staff ones only to staff")
        void completion() {
            CommandSender player = mock(CommandSender.class);
            CommandSender admin = mock(CommandSender.class);
            when(admin.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

            assertThat(command.suggest(from(player), new String[]{""})).contains("menu", "start", "stats", "hud")
                    .doesNotContain("reset", "seed", "resume");
            assertThat(command.suggest(from(admin), new String[]{"re"})).containsExactlyInAnyOrder("reset", "resume");
            assertThat(command.suggest(from(admin), new String[]{"hud", "s"})).containsExactly("sidebar");
            assertThat(command.suggest(from(admin), new String[]{"seed", ""})).containsExactly("random", "same");
        }

        @Test
        @DisplayName("an unknown word is said, and the list of what it does follows")
        void unknown() {
            CommandSender sender = mock(CommandSender.class);

            command.execute(from(sender), new String[]{"strat"});

            verify(messages).send(sender, "speedrun.command.unknown", "word", "strat");
            verify(messages).send(sender, "speedrun.command.help-header");
        }

        @Test
        @DisplayName("a staff word from somebody else is refused, saying it is staff's")
        void staffOnly() {
            CommandSender sender = mock(CommandSender.class);

            command.execute(from(sender), new String[]{"reset"});

            verify(messages).send(sender, "speedrun.command.staff-only", "word", "reset");
        }

        @Test
        @DisplayName("the console resets only when it adds 'confirm'")
        void consoleConfirms() {
            CommandSender console = mock(CommandSender.class);
            when(console.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

            command.execute(from(console), new String[]{"reset"});

            verify(messages).send(console, "speedrun.reset.console-confirm");
        }

        @Test
        @DisplayName("seed: a seed sets FIXED, 'random' goes back, 'same' replays this map once")
        void seeds() {
            CommandSender console = mock(CommandSender.class);
            when(console.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

            command.execute(from(console), new String[]{"seed", "-123"});
            assertThat(lobby.config().seedMode()).isEqualTo(SpeedrunSeedMode.FIXED);
            assertThat(lobby.config().seed()).isEqualTo("-123");

            command.execute(from(console), new String[]{"seed", "random"});
            assertThat(lobby.config().seedMode()).isEqualTo(SpeedrunSeedMode.RANDOM);

            command.execute(from(console), new String[]{"seed", "same"});
            assertThat(lobby.replayingSeed()).isTrue();
        }

        @Test
        @DisplayName("time and resume read a clock, and refuse what is not one")
        void clockWords() {
            CommandSender console = mock(CommandSender.class);
            when(console.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

            command.execute(from(console), new String[]{"time", "soon"});
            command.execute(from(console), new String[]{"time", "1:00"});

            verify(messages).send(console, "speedrun.time.unreadable", "time", "soon");
            verify(messages).send(console, "speedrun.time.no-run", "time", "1:00");
        }

        @Test
        @DisplayName("the console's check lists every check, one line each")
        void check() {
            CommandSender console = mock(CommandSender.class);

            command.execute(from(console), new String[]{"check"});

            verify(messages).send(console, "speedrun.check.not-clear");
            verify(console, org.mockito.Mockito.atLeast(4)).sendMessage(any(Component.class));
        }

        @Test
        @DisplayName("stats for somebody who never raced says so")
        void statsOfNobody() {
            CommandSender console = mock(CommandSender.class);
            Player alice = mock(Player.class);
            when(alice.getName()).thenReturn("Alice");
            when(alice.getUniqueId()).thenReturn(ALICE);
            bukkit.when(() -> Bukkit.getPlayerExact("Alice")).thenReturn(alice);

            command.execute(from(console), new String[]{"stats", "Alice"});

            verify(messages).send(console, "speedrun.stats.none", "player", "Alice");
        }
    }

    @Nested
    @DisplayName("the setup assistant")
    class Setup {

        @Test
        @DisplayName("asks about the game only when one is installed, and about deaths only when they matter")
        void steps() {
            SpeedrunLobby lobby = new SpeedrunLobby(plugin, settings);
            assertThat(SpeedrunSetupMenu.steps(lobby)).doesNotContain(SpeedrunSetupMenu.Step.GAME)
                    .contains(SpeedrunSetupMenu.Step.DEATHS).endsWith(SpeedrunSetupMenu.Step.DONE);

            SpeedrunModes.offer(new SpeedrunMode() {
                public String id() { return "hunt"; }
                public String label() { return "Hunt"; }
                public Material icon() { return Material.STONE; }
                public Optional<String> refuseStart(SpeedrunSettings c, Set<UUID> p) { return Optional.empty(); }
                public boolean usesDeathPolicy() { return false; }
                public void onStart(SpeedrunRun run) { }
            });
            settings.set("game-mode", "hunt");

            assertThat(SpeedrunSetupMenu.steps(lobby)).startsWith(SpeedrunSetupMenu.Step.GAME)
                    .doesNotContain(SpeedrunSetupMenu.Step.DEATHS);
        }
    }

    @Nested
    @DisplayName("the splits HUD")
    class Hud {

        private SpeedrunPlayerPrefs prefs() {
            SpeedrunPlayerPrefs prefs = new SpeedrunPlayerPrefs(new YamlStore(folder.resolve("players.yml")), Runnable::run);
            prefs.load();
            return prefs;
        }

        @Test
        @DisplayName("each player's choice is kept across a restart; without one they follow hud-default")
        void choicesPersist() {
            SpeedrunPlayerPrefs before = prefs();
            assertThat(before.hudOf(ALICE, SpeedrunHudMode.SIDEBAR)).isEqualTo(SpeedrunHudMode.SIDEBAR);
            before.hud(ALICE, SpeedrunHudMode.BOSSBAR);

            SpeedrunPlayerPrefs after = prefs();
            assertThat(after.hudOf(ALICE, SpeedrunHudMode.SIDEBAR)).isEqualTo(SpeedrunHudMode.BOSSBAR);
            assertThat(after.chose(ALICE)).isTrue();
        }

        @Test
        @DisplayName("the sidebar lists the splits with their deltas, then what is next and the pearls")
        void sidebar() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.start();
            SpeedrunSplitTracker tracker = new SpeedrunSplitTracker(session);
            tracker.reach("enter-nether", ALICE);
            tracker.pearlsPickedUp(ALICE, 3, 12);
            tracker.addHudLines(viewer -> List.of(Component.text("Runners left: 2")));
            SpeedrunHud hud = new SpeedrunHud(null, null, prefs(), () -> settings.current(), task -> () -> { });

            List<String> lines = hud.sidebar(ALICE, session, tracker).lines().stream()
                    .map(line -> PlainTextComponentSerializer.plainText().serialize(line)).toList();

            assertThat(lines.getFirst()).startsWith("Nether 0:0");
            assertThat(lines).contains("» Bastion", "Pearls 3/12", "Runners left: 2");
        }

        @Test
        @DisplayName("the action bar shows the last split only for somebody who chose it")
        void actionBar() {
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.start();
            SpeedrunSplitTracker tracker = new SpeedrunSplitTracker(session);
            tracker.reach("enter-nether", ALICE);
            SpeedrunPlayerPrefs prefs = prefs();
            SpeedrunHud hud = new SpeedrunHud(null, null, prefs, () -> settings.current(), task -> () -> { });
            hud.start(session, tracker, Set::of);

            assertThat(hud.actionBarSuffix(ALICE)).isEqualTo(Component.empty());
            prefs.hud(ALICE, SpeedrunHudMode.ACTIONBAR);
            assertThat(PlainTextComponentSerializer.plainText().serialize(hud.actionBarSuffix(ALICE)))
                    .contains("Nether");
        }

        @Test
        @DisplayName("the clock carries whatever the HUD hangs after it")
        void clockSuffix() {
            Map<UUID, Component> shown = new HashMap<>();
            ActionBarSink sink = shown::put;
            SpeedrunTimerDisplay display = new SpeedrunTimerDisplay(new ActionBars(sink, () -> 0L), task -> () -> { });
            display.alsoAppend(viewer -> Component.text(" +split"));
            SpeedrunSession session = new SpeedrunSession(Set.of(ALICE));
            session.start();

            display.start(session);

            assertThat(PlainTextComponentSerializer.plainText().serialize(shown.get(ALICE))).isEqualTo("0:00 +split");
        }

        @Test
        @DisplayName("the next place round goes sidebar → boss bar → action bar → off → sidebar")
        void cycle() {
            assertThat(SpeedrunHudMode.SIDEBAR.next()).isEqualTo(SpeedrunHudMode.BOSSBAR);
            assertThat(SpeedrunHudMode.OFF.next()).isEqualTo(SpeedrunHudMode.SIDEBAR);
        }
    }

    @Test
    @DisplayName("a practice kit is handed out on top of the clean slate, in full")
    void practiceKit() {
        assertThat(SpeedrunPracticeKit.BLAZE_AND_PEARLS.items())
                .containsExactly(Map.entry(Material.BLAZE_ROD, 7), Map.entry(Material.ENDER_PEARL, 14));
        assertThat(SpeedrunPracticeKit.NONE.isPractice()).isFalse();
        assertThat(SpeedrunPracticeKit.DRAGON_FIGHT.isPractice()).isTrue();
    }

    @Test
    @DisplayName("defaults make a good run with zero configuration")
    void zeroConfig() {
        SpeedrunSettings defaults = SpeedrunSettings.DEFAULTS;

        assertThat(defaults.isDragonKillGoal()).isTrue();
        assertThat(defaults.seedMode()).isEqualTo(SpeedrunSeedMode.RANDOM);
        assertThat(defaults.hudDefault()).isEqualTo(SpeedrunHudMode.SIDEBAR);
        assertThat(defaults.pearlsToCollect()).isEqualTo(12);
        assertThat(defaults.splitAnnouncements()).isTrue();
        assertThat(defaults.goldSplitCelebration()).isTrue();
        assertThat(defaults.rankEditedRuns()).isFalse();
        assertThat(defaults.kit()).isEqualTo(SpeedrunPracticeKit.NONE);
        assertThat(defaults.setupDone()).isFalse();
    }
}
