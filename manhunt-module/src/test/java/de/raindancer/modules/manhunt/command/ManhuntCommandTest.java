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
}
