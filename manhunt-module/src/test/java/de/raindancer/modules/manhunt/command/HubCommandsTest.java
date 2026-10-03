package de.raindancer.modules.manhunt.command;

import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The one obvious door, and every word behind it — what an admin and a player can each do. */
@DisplayName("/manhunt, as a front door")
class HubCommandsTest {

    private static final UUID ANNA = UUID.nameUUIDFromBytes("anna".getBytes());
    private static final UUID BEN = UUID.nameUUIDFromBytes("ben".getBytes());
    private static final UUID CARO = UUID.nameUUIDFromBytes("caro".getBytes());

    @TempDir
    Path directory;

    private FakeServices fake;
    private ManhuntCommand command;
    private CommandSourceStack source;
    private Player anna;
    private CommandSender console;
    private MockedStatic<Bukkit> bukkit;

    @BeforeEach
    void setUp() {
        fake = new FakeServices(directory);
        when(fake.mode.current()).thenReturn(Optional.empty());
        command = new ManhuntCommand(() -> fake.services);
        anna = mock(Player.class);
        when(anna.getUniqueId()).thenReturn(ANNA);
        when(anna.getName()).thenReturn("Anna");
        source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(anna);
        console = mock(CommandSender.class);
        when(console.hasPermission(any(String.class))).thenReturn(true);
        fake.present.addAll(List.of(ANNA, BEN, CARO));
        fake.names.put(ANNA, "Anna");
        fake.names.put(BEN, "Ben");
        fake.names.put(CARO, "Caro");
        bukkit = mockStatic(Bukkit.class);
    }

    @AfterEach
    void close() {
        bukkit.close();
    }

    private void admin() {
        when(anna.hasPermission(PermissionNodes.ADMIN)).thenReturn(true);
    }

    private void asConsole(String... args) {
        when(source.getSender()).thenReturn(console);
        command.execute(source, args);
    }

    private void run(String... args) {
        command.execute(source, args);
    }

    @Test
    @DisplayName("/manhunt alone opens the hub — for everybody; the hub knows who is asking")
    void hub() {
        run();
        run("hub");

        assertThat(fake.pages).containsExactly("Anna:HUB", "Anna:HUB");
    }

    @Test
    @DisplayName("sides: the editor for an admin, the hub for anybody else")
    void sides() {
        run("sides");
        admin();
        run("sides");

        assertThat(fake.pages).containsExactly("Anna:HUB", "Anna:SIDES");
    }

    @Test
    @DisplayName("an admin's pages: pre-flight, goal, resume, setup; a player is told they are not theirs")
    void adminPages() {
        run("preflight");
        verify(fake.messages).send(eq(anna), eq("manhunt.not-yours"), any(Object[].class));
        admin();
        run("preflight");
        run("goal");
        run("resume");
        run("setup");

        assertThat(fake.pages).containsExactly("Anna:PREFLIGHT", "Anna:GOAL", "Anna:RESUME", "Anna:SETUP");
    }

    @Test
    @DisplayName("pre-flight from the console is said line by line, each one with its fix")
    void preflightInChat() {
        asConsole("preflight");

        verify(fake.messages).send(eq(console), eq("manhunt.preflight.header"), any(Object[].class));
        verify(fake.messages).send(eq(console), eq("manhunt.preflight.no-runner"), any(Object[].class));
        verify(fake.messages).send(eq(console), eq("manhunt.preflight.blocked"), any(Object[].class));
    }

    @Test
    @DisplayName("balance splits everybody here and says how even it is")
    void balance() {
        admin();
        run("balance");

        assertThat(fake.teams.everybody()).containsExactlyInAnyOrder(ANNA, BEN, CARO);
        assertThat(fake.teams.runners()).isNotEmpty();
        verify(fake.messages).send(eq(anna), eq("manhunt.balance.done"), any(Object[].class));
    }

    @Test
    @DisplayName("random picks that many Runners, one by default")
    void random() {
        admin();
        run("random");
        assertThat(fake.teams.runners()).hasSize(1);

        run("random", "2");
        assertThat(fake.teams.runners()).hasSize(2);
        verify(fake.messages, atLeastOnce()).send(eq(anna), eq("manhunt.random.done"), any(Object[].class));

        run("random", "lots");
        verify(fake.messages).send(eq(anna), eq("manhunt.random.usage"), any(Object[].class));
    }

    @Test
    @DisplayName("balancing is refused mid-hunt, saying why")
    void balanceMidHunt() {
        admin();
        when(fake.mode.isRunning()).thenReturn(true);

        run("balance");

        verify(fake.messages).send(eq(anna), eq("manhunt.sides-frozen"), any(Object[].class));
    }

    @Test
    @DisplayName("unassign takes somebody off their side before a hunt")
    void unassign() {
        admin();
        fake.teams.joinRunners(BEN);
        Player ben = mock(Player.class);
        when(ben.getUniqueId()).thenReturn(BEN);
        when(ben.getName()).thenReturn("Ben");
        bukkit.when(() -> Bukkit.getPlayerExact("Ben")).thenReturn(ben);

        run("unassign", "Ben");

        assertThat(fake.teams.everybody()).doesNotContain(BEN);
        verify(fake.messages).send(eq(anna), eq("manhunt.unassign.done"), any(Object[].class));
    }

    @Test
    @DisplayName("goal set takes a known advancement and refuses an unknown one")
    void goalSet() {
        admin();
        run("goal", "set", "minecraft:story/enter_the_nether");
        run("goal", "set", "minecraft:made/up");

        assertThat(fake.desk.goal()).isEqualTo("minecraft:story/enter_the_nether");
        verify(fake.messages).send(eq(anna), eq("manhunt.goal.set"), any(Object[].class));
        verify(fake.messages).send(eq(anna), eq("manhunt.goal.unknown"), any(Object[].class));
    }

    @Test
    @DisplayName("door keep-open and close-on-start flip the whitelist setting")
    void door() {
        admin();
        run("door", "close-on-start");
        assertThat(fake.settings.current().closeWhitelistOnStart()).isTrue();

        run("door", "keep-open");
        assertThat(fake.settings.current().closeWhitelistOnStart()).isFalse();
        verify(fake.messages).send(eq(anna), eq("manhunt.door.kept-open"), any(Object[].class));
    }

    @Test
    @DisplayName("setup applies a preset and remembers the server is set up; skip only remembers")
    void setup() {
        admin();
        run("setup", "casual");

        assertThat(fake.settings.current().runnerLives()).isEqualTo(2);
        assertThat(fake.setup.done()).isTrue();
        verify(fake.messages).send(eq(anna), eq("manhunt.setup.applied"), any(Object[].class));

        run("setup", "nonsense");
        verify(fake.messages).send(eq(anna), eq("manhunt.setup.usage"), any(Object[].class));
    }

    @Test
    @DisplayName("stats: your own page, somebody else's by name, and a name nobody played under")
    void stats() {
        run("stats");
        assertThat(fake.statsPages).containsExactly("Anna:" + ANNA);

        run("stats", "Nobody");
        verify(fake.messages).send(eq(anna), eq("manhunt.stats.unknown"), any(Object[].class));
    }

    @Test
    @DisplayName("the console gets stats, the leaderboard and the history in words")
    void inWords() {
        asConsole("top");
        verify(fake.messages).send(eq(console), eq("manhunt.top.empty"), any(Object[].class));

        asConsole("history");
        verify(fake.messages).send(eq(console), eq("manhunt.history.none"), any(Object[].class));

        asConsole("top", "nonsense");
        verify(fake.messages).send(eq(console), eq("manhunt.top.usage"), any(Object[].class));
    }

    @Test
    @DisplayName("leaderboard and history pages for a player; a summary by number, the newest by default")
    void pagesForPlayers() {
        run("top");
        run("history");
        run("summary");
        verify(fake.messages).send(eq(anna), eq("manhunt.summary.none"), any(Object[].class));

        run("summary", "3");

        assertThat(fake.pages).containsExactly("Anna:LEADERBOARD", "Anna:HISTORY");
        assertThat(fake.summaryPages).isEmpty();
    }

    @Test
    @DisplayName("hud and announcements are each player's own switches")
    void switches() {
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(anna.getPersistentDataContainer()).thenReturn(data);

        run("hud");
        run("announcements");

        verify(fake.messages).send(eq(anna), eq("manhunt.hud.off"), any(Object[].class));
        verify(fake.messages).send(eq(anna), eq("manhunt.announcements.off"), any(Object[].class));
    }

    @Test
    @DisplayName("every word is offered, and every argument the words take")
    void completesEverything() {
        admin();
        assertThat(command.suggest(source, new String[]{""})).contains("hub", "sides", "preflight", "balance",
                "random", "unassign", "door", "stats", "top", "history", "summary", "setup", "hud",
                "announcements", "start", "resume", "goal", "give");
        assertThat(command.suggest(source, new String[]{"door", ""})).containsExactly("keep-open", "close-on-start");
        assertThat(command.suggest(source, new String[]{"setup", ""})).contains("classic", "casual", "sweaty", "skip");
        assertThat(command.suggest(source, new String[]{"top", ""})).contains("rating", "wins", "catches");
        assertThat(command.suggest(source, new String[]{"goal", ""})).containsExactly("remove", "set");
        assertThat(command.suggest(source, new String[]{"goal", "set", "minecraft:story"}))
                .contains("minecraft:story/enter_the_nether");
        assertThat(command.suggest(source, new String[]{"random", ""})).contains("1", "2");
        assertThat(command.suggest(source, new String[]{"resume", ""})).contains("0:00");
    }

    @Test
    @DisplayName("a player is only offered the words that are theirs")
    void completesForPlayers() {
        assertThat(command.suggest(source, new String[]{""})).contains("join", "leave", "stats", "hud")
                .doesNotContain("balance", "start", "setup");
    }

    @Test
    @DisplayName("an unknown word points at the hub")
    void unknown() {
        run("dance");

        verify(fake.messages).send(eq(anna), eq("manhunt.unknown-word"), any(Object[].class));
        verify(fake.messages, never()).send(eq(anna), eq("manhunt.not-yours"), any(Object[].class));
    }
}
