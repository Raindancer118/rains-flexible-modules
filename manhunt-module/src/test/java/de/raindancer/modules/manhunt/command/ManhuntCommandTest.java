package de.raindancer.modules.manhunt.command;

import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
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

/** Picking a side, and the two moments where that is not the player's to do. */
class ManhuntCommandTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());

    @TempDir
    Path directory;

    private FakeServices fake;
    private ManhuntCommand command;
    private CommandSourceStack source;
    private Player anna;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        fake = new FakeServices(directory);
        when(fake.mode.isRunning()).thenReturn(false);
        when(fake.mode.current()).thenReturn(Optional.empty());
        command = new ManhuntCommand(() -> fake.services);
        anna = mock(Player.class);
        when(anna.getUniqueId()).thenReturn(ANNA);
        when(anna.getName()).thenReturn("Anna");
        source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(anna);
    }

    @AfterEach
    void closeStatic() {
        if (bukkit != null) {
            bukkit.close();
        }
    }

    @Test
    @DisplayName("join runner puts the caller on the Runner side")
    void joinRunner() {
        command.execute(source, new String[]{"join", "runner"});

        assertThat(fake.teams.runners()).containsExactly(ANNA);
        verify(fake.messages).send(eq(anna), eq("manhunt.join.runner"), any(Object[].class));
    }

    @Test
    @DisplayName("join hunter takes them off the Runners again")
    void joinHunter() {
        command.execute(source, new String[]{"join", "runner"});
        command.execute(source, new String[]{"join", "hunter"});

        assertThat(fake.teams.runners()).isEmpty();
        assertThat(fake.teams.hunters()).containsExactly(ANNA);
    }

    @Test
    @DisplayName("with self-joining locked, only an admin may take the Runner side")
    void runnersCanBeLocked() {
        fake.settings.set("runner-self-join", "false");
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(false);

        command.execute(source, new String[]{"join", "runner"});

        assertThat(fake.teams.runners()).isEmpty();
        verify(fake.messages).send(eq(anna), eq("manhunt.join.runners-locked"), any(Object[].class));
    }

    @Test
    @DisplayName("the lock never stops anybody from hunting")
    void huntingIsNeverLocked() {
        fake.settings.set("runner-self-join", "false");

        command.execute(source, new String[]{"join", "hunter"});

        assertThat(fake.teams.hunters()).containsExactly(ANNA);
    }

    @Test
    @DisplayName("sides cannot change while a hunt is being played")
    void frozenDuringAHunt() {
        when(fake.mode.isRunning()).thenReturn(true);
        when(fake.mode.changeSide(any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(de.raindancer.modules.manhunt.mode.ManhuntMode.SideChange.FROZEN);

        command.execute(source, new String[]{"join", "runner"});

        assertThat(fake.teams.runners()).isEmpty();
        verify(fake.messages).send(eq(anna), eq("manhunt.sides-frozen"), any(Object[].class));
    }

    @Test
    @DisplayName("mid-hunt, a join goes through the mode, which moves the compass with it")
    void joiningMidHuntGoesThroughTheMode() {
        when(fake.mode.isRunning()).thenReturn(true);
        when(fake.mode.changeSide(ANNA, de.raindancer.modules.manhunt.mode.ManhuntMode.Side.HUNTER, false))
                .thenReturn(de.raindancer.modules.manhunt.mode.ManhuntMode.SideChange.CHANGED);

        command.execute(source, new String[]{"join", "hunter"});

        verify(fake.mode).changeSide(ANNA,
                de.raindancer.modules.manhunt.mode.ManhuntMode.Side.HUNTER, false);
        assertThat(fake.teams.hunters())
                .as("the team is the mode's to move, not the command's")
                .isEmpty();
    }

    @Test
    @DisplayName("with the Runners hand-picked, not even an admin can choose to run")
    void handPickedRunnersHaveNoAdminBackDoor() {
        fake.settings.set("runner-self-join", "false");
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

        command.execute(source, new String[]{"join", "runner"});

        assertThat(fake.teams.runners()).isEmpty();
        verify(fake.messages).send(eq(anna), eq("manhunt.join.runners-locked"), any(Object[].class));
    }

    @Test
    @DisplayName("reset empties both sides, and needs the admin node")
    void resetClearsBothSides() {
        fake.teams.joinRunners(ANNA);
        fake.teams.joinHunters(BEN);
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

        command.execute(source, new String[]{"reset"});

        assertThat(fake.teams.everybody()).isEmpty();
        verify(fake.messages).send(eq(anna), eq("manhunt.reset.done"), any(Object[].class));
    }

    @Test
    @DisplayName("reset is refused to somebody without the admin node")
    void resetNeedsThePermission() {
        fake.teams.joinRunners(ANNA);

        command.execute(source, new String[]{"reset"});

        assertThat(fake.teams.runners()).containsExactly(ANNA);
        verify(fake.messages).send(eq(anna), eq("manhunt.not-yours"), any(Object[].class));
    }

    @Test
    @DisplayName("leave mid-hunt takes you out of the hunt entirely, compasses and all")
    void leaveMidHunt() {
        when(fake.mode.isRunning()).thenReturn(true);
        when(fake.mode.leaveHunt(ANNA)).thenReturn(
                de.raindancer.modules.manhunt.mode.ManhuntMode.LeaveOutcome.LEFT);

        command.execute(source, new String[]{"leave"});

        verify(fake.mode).leaveHunt(ANNA);
        assertThat(fake.compassesTaken).containsExactly(anna);
        verify(fake.messages).send(eq(anna), eq("manhunt.left-hunt"), any(Object[].class));
    }

    @Test
    @DisplayName("goal remove clears the goal, also in the middle of a hunt — an admin's call")
    void goalRemove() {
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);
        command = new ManhuntCommand(() -> fake.services, new FakeLobby());

        command.execute(source, new String[]{"goal", "remove"});

        verify(fake.messages).send(eq(anna), eq("manhunt.goal.removed-mid-hunt"), any(Object[].class));
    }

    /** The speedrun lobby as the command sees it, answering whatever a test sets. */
    static final class FakeLobby implements ManhuntCommand.Lobby {
        java.time.Duration resumedAt;
        boolean started;
        de.raindancer.modules.speedrun.SpeedrunControl.Answer answer =
                new de.raindancer.modules.speedrun.SpeedrunControl.Answer(
                        de.raindancer.modules.speedrun.SpeedrunLobby.StartOutcome.STARTED, "speedrun.start.started", 3);

        @Override
        public Optional<de.raindancer.modules.speedrun.SpeedrunLobby.GoalRemoval> removeGoal() {
            return Optional.of(de.raindancer.modules.speedrun.SpeedrunLobby.GoalRemoval.REMOVED_FROM_RUN);
        }

        @Override
        public Optional<de.raindancer.modules.speedrun.SpeedrunControl.Answer> start() {
            started = true;
            return Optional.of(answer);
        }

        @Override
        public Optional<de.raindancer.modules.speedrun.SpeedrunControl.Answer> resume(java.time.Duration already) {
            resumedAt = already;
            return Optional.of(answer);
        }
    }

    @Test
    @DisplayName("start begins a hunt from the command, as the start block would")
    void start() {
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);
        FakeLobby lobby = new FakeLobby();
        command = new ManhuntCommand(() -> fake.services, lobby);

        command.execute(source, new String[]{"start"});

        assertThat(lobby.started).isTrue();
        verify(fake.messages).send(eq(anna), eq("speedrun.start.started"), any(Object[].class));
    }

    @Test
    @DisplayName("resume picks the hunt up at the given time; a refusal is said in its own words")
    void resume() {
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);
        FakeLobby lobby = new FakeLobby();
        command = new ManhuntCommand(() -> fake.services, lobby);

        command.execute(source, new String[]{"resume", "42:05"});
        assertThat(lobby.resumedAt).isEqualTo(java.time.Duration.ofMinutes(42).plusSeconds(5));
        verify(fake.messages).send(eq(anna), eq("manhunt.resume.done"), any(Object[].class));

        lobby.answer = new de.raindancer.modules.speedrun.SpeedrunControl.Answer(
                de.raindancer.modules.speedrun.SpeedrunLobby.StartOutcome.REFUSED_BY_MODE, "manhunt.start.no-runner", 2);
        command.execute(source, new String[]{"resume"});
        assertThat(lobby.resumedAt).isEqualTo(java.time.Duration.ZERO);
        verify(fake.messages).send(eq(anna), eq("manhunt.start.no-runner"), any(Object[].class));

        command.execute(source, new String[]{"resume", "soon"});
        verify(fake.messages).send(eq(anna), eq("speedrun.time.unreadable"), any(Object[].class));
    }

    @Test
    @DisplayName("start and resume are an admin's")
    void startAdminOnly() {
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(false);
        FakeLobby lobby = new FakeLobby();
        command = new ManhuntCommand(() -> fake.services, lobby);

        command.execute(source, new String[]{"start"});
        command.execute(source, new String[]{"resume", "10:00"});

        assertThat(lobby.started).isFalse();
        assertThat(lobby.resumedAt).isNull();
    }

    @Test
    @DisplayName("goal remove is not for players")
    void goalRemoveAdminOnly() {
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(false);

        command.execute(source, new String[]{"goal", "remove"});

        verify(fake.messages).send(eq(anna), eq("manhunt.not-yours"), any(Object[].class));
    }

    @Test
    @DisplayName("reset is refused while a hunt is being played")
    void resetIsRefusedMidHunt() {
        fake.teams.joinRunners(ANNA);
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);
        when(fake.mode.isRunning()).thenReturn(true);

        command.execute(source, new String[]{"reset"});

        assertThat(fake.teams.runners()).containsExactly(ANNA);
        verify(fake.messages).send(eq(anna), eq("manhunt.sides-frozen"), any(Object[].class));
    }

    @Test
    @DisplayName("assign needs the admin node, and puts somebody else on a side")
    void assign() {
        Player ben = mock(Player.class);
        when(ben.getUniqueId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayerExact("Ben")).thenReturn(ben);
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

        command.execute(source, new String[]{"assign", "Ben", "runner"});

        assertThat(fake.teams.runners()).containsExactly(BEN);
    }

    @Test
    @DisplayName("assign without the node changes nobody")
    void assignIsGated() {
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(false);

        command.execute(source, new String[]{"assign", "Ben", "runner"});

        assertThat(fake.teams.runners()).isEmpty();
        verify(fake.messages).send(eq(anna), eq("manhunt.not-yours"), any(Object[].class));
    }

    @Test
    @DisplayName("with no argument a player gets the sides screen")
    void noArgumentOpensTheScreen() {
        command.execute(source, new String[]{});

        assertThat(fake.screensOpenedFor).containsExactly(anna);
    }

    @Test
    @DisplayName("the console gets the rosters in words instead of a screen it cannot open")
    void consoleGetsStatus() {
        CommandSender console = mock(CommandSender.class);
        when(source.getSender()).thenReturn(console);
        bukkit = mockStatic(Bukkit.class);

        command.execute(source, new String[]{});

        assertThat(fake.screensOpenedFor).isEmpty();
        verify(fake.messages).send(eq(console), eq("manhunt.status.waiting"), any(Object[].class));
    }

    @Test
    @DisplayName("status during a hunt counts who is left rather than who signed up")
    void statusDuringAHunt() {
        Hunt hunt = Hunt.of(Set.of(ANNA, BEN), Set.of(ANNA));
        when(fake.mode.current()).thenReturn(Optional.of(hunt));
        bukkit = mockStatic(Bukkit.class);
        var offline = mock(org.bukkit.OfflinePlayer.class);
        when(offline.getName()).thenReturn("Anna");
        bukkit.when(() -> Bukkit.getOfflinePlayer(any(UUID.class))).thenReturn(offline);

        command.execute(source, new String[]{"status"});

        verify(fake.messages).send(eq(anna), eq("manhunt.status.running"), any(Object[].class));
    }

    @Test
    @DisplayName("a word /manhunt does not have is answered, not ignored")
    void unknownWord() {
        command.execute(source, new String[]{"chaos"});

        verify(fake.messages).send(eq(anna), eq("manhunt.unknown-word"), any(Object[].class));
    }

    @Test
    @DisplayName("tab completion offers the sides, and the online players for assign")
    void completion() {
        assertThat(command.suggest(source, new String[]{""}))
                .contains("join", "leave", "assign", "status");
        assertThat(command.suggest(source, new String[]{"join", "r"})).containsExactly("runner");
    }

    @Test
    @DisplayName("assign mid-hunt asks a player first, and moves nobody until they say yes")
    void assignMidHuntAsksAPlayer() {
        Player ben = mock(Player.class);
        when(ben.getUniqueId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayerExact("Ben")).thenReturn(ben);
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);
        when(fake.mode.isRunning()).thenReturn(true);
        when(fake.mode.changeSide(BEN, de.raindancer.modules.manhunt.mode.ManhuntMode.Side.HUNTER, true))
                .thenReturn(de.raindancer.modules.manhunt.mode.ManhuntMode.SideChange.CHANGED);

        command.execute(source, new String[]{"assign", "Ben", "hunter"});

        assertThat(fake.confirmationsAskedOf).containsExactly(anna);
        verify(fake.mode, org.mockito.Mockito.never()).changeSide(any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());

        fake.lastConfirmation.run();   // they clicked yes

        verify(fake.mode).changeSide(BEN,
                de.raindancer.modules.manhunt.mode.ManhuntMode.Side.HUNTER, true);
    }

    @Test
    @DisplayName("the console is not asked — it meant what it typed")
    void assignMidHuntFromConsoleGoesStraightThrough() {
        Player ben = mock(Player.class);
        when(ben.getUniqueId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        bukkit = mockStatic(Bukkit.class);
        bukkit.when(() -> Bukkit.getPlayerExact("Ben")).thenReturn(ben);
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        when(source.getSender()).thenReturn(console);
        when(fake.mode.isRunning()).thenReturn(true);
        when(fake.mode.changeSide(BEN, de.raindancer.modules.manhunt.mode.ManhuntMode.Side.RUNNER, true))
                .thenReturn(de.raindancer.modules.manhunt.mode.ManhuntMode.SideChange.CHANGED);

        command.execute(source, new String[]{"assign", "Ben", "runner"});

        assertThat(fake.confirmationsAskedOf).isEmpty();
        verify(fake.mode).changeSide(BEN,
                de.raindancer.modules.manhunt.mode.ManhuntMode.Side.RUNNER, true);
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("/manhunt trail — each player's own particle trail")
    class Trail {

        private final java.util.Map<org.bukkit.NamespacedKey, Boolean> stored = new java.util.HashMap<>();

        @BeforeEach
        void playerData() {
            org.bukkit.persistence.PersistentDataContainer data =
                    mock(org.bukkit.persistence.PersistentDataContainer.class);
            when(anna.getPersistentDataContainer()).thenReturn(data);
            when(data.get(any(org.bukkit.NamespacedKey.class),
                    eq(org.bukkit.persistence.PersistentDataType.BOOLEAN)))
                    .thenAnswer(call -> stored.get(call.getArgument(0, org.bukkit.NamespacedKey.class)));
            org.mockito.Mockito.doAnswer(call -> stored.put(call.getArgument(0), call.getArgument(2)))
                    .when(data).set(any(org.bukkit.NamespacedKey.class),
                            eq(org.bukkit.persistence.PersistentDataType.BOOLEAN), any(Boolean.class));
        }

        @Test
        @DisplayName("switches it off for them, and on again")
        void toggles() {
            command.execute(source, new String[]{"trail"});
            assertThat(de.raindancer.modules.manhunt.tracker.TrailPreference.shows(anna, fake.settings.current()))
                    .isFalse();
            verify(fake.messages).send(eq(anna), eq("manhunt.trail.off"), any(Object[].class));

            command.execute(source, new String[]{"trail"});
            assertThat(de.raindancer.modules.manhunt.tracker.TrailPreference.shows(anna, fake.settings.current()))
                    .isTrue();
            verify(fake.messages).send(eq(anna), eq("manhunt.trail.on"), any(Object[].class));
        }

        @Test
        @DisplayName("where the server has the trail off, a player cannot switch it on for themselves")
        void serverOff() {
            fake.settings.set("tracker-particle-trail", "false");

            command.execute(source, new String[]{"trail"});

            verify(fake.messages).send(eq(anna), eq("manhunt.trail.server-off"), any(Object[].class));
            assertThat(stored).isEmpty();
        }

        @Test
        @DisplayName("the console has no trail to switch")
        void console() {
            org.bukkit.command.CommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
            when(source.getSender()).thenReturn(console);

            command.execute(source, new String[]{"trail"});

            verify(fake.messages).send(eq(console), eq("manhunt.only-a-player"), any(Object[].class));
        }

        @Test
        @DisplayName("and it is offered when typing")
        void completes() {
            assertThat(command.suggest(source, new String[]{"tr"})).containsExactly("trail");
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("/manhunt here")
    class Here {

        @Test
        @DisplayName("shares where the caller stands")
        void shares() {
            command.execute(source, new String[]{"here"});

            verify(fake.share).share(anna);
        }

        @Test
        @DisplayName("here stop ends the caller's navigation, and says whether there was one")
        void stops() {
            when(fake.share.stop(anna)).thenReturn(true);
            command.execute(source, new String[]{"here", "stop"});
            verify(fake.messages).send(eq(anna), eq("manhunt.here.stopped"), any(Object[].class));

            when(fake.share.stop(anna)).thenReturn(false);
            command.execute(source, new String[]{"here", "stop"});
            verify(fake.messages).send(eq(anna), eq("manhunt.here.not-navigating"), any(Object[].class));
        }

        @Test
        @DisplayName("the console stands nowhere")
        void console() {
            org.bukkit.command.CommandSender console = mock(org.bukkit.command.ConsoleCommandSender.class);
            when(source.getSender()).thenReturn(console);

            command.execute(source, new String[]{"here"});

            verify(fake.share, org.mockito.Mockito.never()).share(any());
            verify(fake.messages).send(eq(console), eq("manhunt.only-a-player"), any(Object[].class));
        }

        @Test
        @DisplayName("offered when typing, with stop after it")
        void completes() {
            assertThat(command.suggest(source, new String[]{"he"})).containsExactly("here");
            assertThat(command.suggest(source, new String[]{"here", ""})).containsExactly("stop");
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("/manhunt give")
    class Give {

        private Player ben;

        @BeforeEach
        void ben() {
            ben = mock(Player.class);
            when(ben.getName()).thenReturn("Ben");
            bukkit = mockStatic(Bukkit.class);
            bukkit.when(() -> Bukkit.getPlayerExact("Ben")).thenReturn(ben);
        }

        @Test
        @DisplayName("hands the named compass to the named player")
        void oneKind() {
            when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

            command.execute(source, new String[]{"give", "Ben", "structure"});

            assertThat(fake.compassesGiven).hasSize(1);
            assertThat(fake.compassesGiven.getFirst()).containsExactly(ben,
                    de.raindancer.modules.manhunt.tracker.CompassHandout.Kind.STRUCTURE);
        }

        @Test
        @DisplayName("without a kind, every compass they are owed")
        void everyKind() {
            when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

            command.execute(source, new String[]{"give", "Ben"});

            assertThat(fake.compassesGiven.getFirst()).containsExactly(ben, null);
        }

        @Test
        @DisplayName("all — everybody in the hunt")
        void everybody() {
            when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

            command.execute(source, new String[]{"give", "all", "tracker"});

            assertThat(fake.compassesGiven.getFirst()).containsExactly("all",
                    de.raindancer.modules.manhunt.tracker.CompassHandout.Kind.TRACKER);
        }

        @Test
        @DisplayName("is an admin's to do")
        void adminOnly() {
            when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(false);

            command.execute(source, new String[]{"give", "Ben", "tracker"});

            assertThat(fake.compassesGiven).isEmpty();
            verify(fake.messages).send(eq(anna), eq("manhunt.not-yours"), any(Object[].class));
        }

        @Test
        @DisplayName("an unknown kind or player is said, not guessed at")
        void refusals() {
            when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);

            command.execute(source, new String[]{"give", "Ben", "sword"});
            command.execute(source, new String[]{"give", "Nobody", "tracker"});
            command.execute(source, new String[]{"give"});

            assertThat(fake.compassesGiven).isEmpty();
            verify(fake.messages).send(eq(anna), eq("manhunt.give.unknown-kind"), any(Object[].class));
            verify(fake.messages).send(eq(anna), eq("manhunt.no-such-player"), any(Object[].class));
            verify(fake.messages).send(eq(anna), eq("manhunt.give.usage"), any(Object[].class));
        }

        @Test
        @DisplayName("completes the kinds")
        void completes() {
            when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);
            assertThat(command.suggest(source, new String[]{"give", "Ben", "t"}))
                    .containsExactly("tracker", "team");
        }
    }
}
