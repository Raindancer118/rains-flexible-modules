package de.raindancer.modules.chat.service;

import de.raindancer.core.content.vote.Votes;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.ui.chat.ChatButton;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A poll started on one server is shown and votable on every server, and each player counts once. */
class PollServiceNetworkTest {

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final List<String> ANSWERS = List.of("Yes", "No");

    private final LocalBus.Hub hub = new LocalBus.Hub();

    /** One server of the network: its players, what they were told, its buttons, and the timers it was asked for. */
    private final class Node {
        final Server server = mock(Server.class);
        final Messages messages = mock(Messages.class, this::saying);
        final Map<UUID, Player> players = new HashMap<>();
        final List<String> told = new ArrayList<>();
        final List<String> shown = new ArrayList<>();
        final Map<String, Consumer<UUID>> buttons = new HashMap<>();
        final List<Runnable> timers = new ArrayList<>();
        final PollService polls;

        Node(String id, boolean onNetwork) {
            ChatButtons chatButtons = mock(ChatButtons.class);
            when(chatButtons.label(any())).thenAnswer(label -> {
                String name = label.getArgument(0);
                ChatButton button = mock(ChatButton.class, Mockito.RETURNS_SELF);
                doAnswer(does -> {
                    buttons.put(name, does.getArgument(0));
                    return button;
                }).when(button).does(any());
                return button;
            });
            ConsoleCommandSender console = mock(ConsoleCommandSender.class);
            doAnswer(line -> shown.add(PLAIN.serialize(line.<Component>getArgument(0))))
                    .when(console).sendMessage(any(Component.class));
            when(server.getConsoleSender()).thenReturn(console);
            when(server.getPlayer(any(UUID.class))).thenAnswer(who -> players.get(who.<UUID>getArgument(0)));
            when(server.getOnlinePlayers()).thenAnswer(all -> List.copyOf(players.values()));
            polls = new PollService(server, new Votes(System::currentTimeMillis), chatButtons, messages,
                    ChatSettings.DEFAULTS, (ticks, task) -> timers.add(task));
            LocalBus bus = hub.bus(id);
            NetworkCalls calls = new NetworkCalls(bus);
            polls.network(bus, () -> Optional.of(calls), () -> onNetwork);
        }

        private Object saying(InvocationOnMock call) {
            Object[] args = call.getArguments();
            switch (call.getMethod().getName()) {
                case "raw":
                    return "<answer>";
                case "get":
                case "prefixed":
                    return Component.text(String.join(" ", Arrays.stream(args).map(String::valueOf).toList()));
                case "send":
                    told.add(((Player) args[0]).getName() + ": " + args[1]
                            + (args.length > 2 ? " " + Arrays.toString(Arrays.copyOfRange(args, 2, args.length)) : ""));
                    return null;
                default:
                    return null;
            }
        }

        Player join(String name) {
            Player player = mock(Player.class);
            UUID id = UUID.nameUUIDFromBytes(name.getBytes());
            when(player.getUniqueId()).thenReturn(id);
            when(player.getName()).thenReturn(name);
            doAnswer(line -> shown.add(name + " saw " + PLAIN.serialize(line.<Component>getArgument(0))))
                    .when(player).sendMessage(any(Component.class));
            players.put(id, player);
            return player;
        }

        void click(Player who, String answer) {
            buttons.get(answer).accept(who.getUniqueId());
            settle();
        }

        void timersFire() {
            List<Runnable> due = List.copyOf(timers);
            timers.clear();
            due.forEach(Runnable::run);
            settle();
        }

        boolean sawResultsOf(String question) {
            return shown.stream().anyMatch(line -> line.contains("chat.poll.results") && line.contains(question));
        }
    }

    private void settle() {
        for (int round = 0; round < 4; round++) {
            hub.drain();
        }
    }

    private static final Duration TWO_MINUTES = Duration.ofMinutes(2);

    @Test
    @DisplayName("a poll started on one server is shown on the other, with a button per answer")
    void shownEverywhere() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        Player tom = smp.join("Tom");
        arena.join("Lilly");

        assertThat(smp.polls.start(tom, "Best mob?", ANSWERS, TWO_MINUTES)).isTrue();
        settle();

        assertThat(arena.buttons).containsOnlyKeys("Yes", "No");
        assertThat(arena.shown).anyMatch(line -> line.contains("chat.poll.asked") && line.contains("Best mob?"));
        assertThat(arena.polls.current()).hasValueSatisfying(poll -> {
            assertThat(poll.origin()).isEqualTo("smp");
            assertThat(poll.starterName()).isEqualTo("Tom");
        });
    }

    @Test
    @DisplayName("a vote from the other server is counted by the server that keeps the tally")
    void votedElsewhere() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        Player tom = smp.join("Tom");
        Player lilly = arena.join("Lilly");
        smp.polls.start(tom, "Best mob?", ANSWERS, TWO_MINUTES);
        settle();

        arena.click(lilly, "Yes");

        assertThat(arena.told).containsExactly("Lilly: chat.poll.voted [answer, Yes]");
        smp.polls.showResults(tom);
        assertThat(smp.shown).anyMatch(line -> line.contains("chat.poll.result-line") && line.contains("count 1"));
    }

    @Test
    @DisplayName("a player counts once whichever server they click on: the second click changes the vote")
    void onceNetworkWide() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        Player tom = smp.join("Tom");
        smp.join("Lilly");
        Player lillyOnArena = arena.join("Lilly");
        smp.polls.start(tom, "Best mob?", ANSWERS, TWO_MINUTES);
        settle();

        arena.click(lillyOnArena, "Yes");
        smp.click(smp.players.get(lillyOnArena.getUniqueId()), "Yes");
        smp.click(smp.players.get(lillyOnArena.getUniqueId()), "No");
        arena.click(lillyOnArena, "No");

        assertThat(arena.told).containsExactly("Lilly: chat.poll.voted [answer, Yes]",
                "Lilly: chat.poll.already [answer, No]");
        assertThat(smp.told).containsExactly("Lilly: chat.poll.already [answer, Yes]",
                "Lilly: chat.poll.changed [answer, No]");
        smp.timersFire();
        assertThat(smp.shown).anyMatch(line -> line.contains("chat.poll.result-line") && line.contains("answer No")
                && line.contains("count 1"));
        assertThat(smp.shown).anyMatch(line -> line.contains("chat.poll.result-line") && line.contains("answer Yes")
                && line.contains("count 0"));
    }

    @Test
    @DisplayName("the result is announced on every server when the poll ends, and a new poll may start there")
    void resultEverywhere() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        Player tom = smp.join("Tom");
        Player lilly = arena.join("Lilly");
        smp.polls.start(tom, "Best mob?", ANSWERS, TWO_MINUTES);
        settle();
        arena.click(lilly, "Yes");

        smp.timersFire();

        assertThat(smp.sawResultsOf("Best mob?")).isTrue();
        assertThat(arena.sawResultsOf("Best mob?")).isTrue();
        assertThat(arena.shown).anyMatch(line -> line.contains("chat.poll.winner") && line.contains("Yes"));
        assertThat(arena.polls.current()).isEmpty();
        assertThat(arena.polls.start(lilly, "Again?", ANSWERS, TWO_MINUTES)).isTrue();
    }

    @Test
    @DisplayName("one poll at a time holds across the network")
    void oneAtATime() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        smp.polls.start(smp.join("Tom"), "Best mob?", ANSWERS, TWO_MINUTES);
        settle();

        assertThat(arena.polls.start(arena.join("Lilly"), "Other?", ANSWERS, TWO_MINUTES)).isFalse();

        assertThat(arena.told).containsExactly("Lilly: chat.poll.one-at-a-time");
    }

    @Test
    @DisplayName("/poll results on the other server shows the tally from the server that keeps it")
    void resultsSoFarElsewhere() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        Player tom = smp.join("Tom");
        Player lilly = arena.join("Lilly");
        smp.polls.start(tom, "Best mob?", ANSWERS, TWO_MINUTES);
        settle();
        smp.click(tom, "No");
        arena.click(lilly, "No");

        arena.polls.showResults(lilly);
        settle();

        assertThat(arena.shown).anyMatch(line -> line.contains("Lilly saw") && line.contains("chat.poll.result-line")
                && line.contains("answer No") && line.contains("count 2"));
    }

    @Test
    @DisplayName("/poll end on the other server ends it everywhere for whoever started it or staff, and for nobody else")
    void endedElsewhere() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        Player tom = smp.join("Tom");
        Player tomOnArena = arena.join("Tom");
        Player lilly = arena.join("Lilly");
        smp.polls.start(tom, "Best mob?", ANSWERS, TWO_MINUTES);
        settle();

        arena.polls.endEarly(lilly);
        settle();
        assertThat(arena.told).containsExactly("Lilly: chat.poll.not-yours");
        assertThat(smp.polls.current()).isPresent();

        arena.polls.endEarly(tomOnArena);
        settle();

        assertThat(smp.sawResultsOf("Best mob?")).isTrue();
        assertThat(arena.sawResultsOf("Best mob?")).isTrue();
        assertThat(arena.polls.current()).isEmpty();
    }

    @Test
    @DisplayName("staff may end a poll from another server")
    void staffEndsElsewhere() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        smp.polls.start(smp.join("Tom"), "Best mob?", ANSWERS, TWO_MINUTES);
        Player staff = arena.join("Mod");
        when(staff.hasPermission(PermissionNodes.POLL_MANAGE)).thenReturn(true);
        settle();

        arena.polls.endEarly(staff);
        settle();

        assertThat(smp.polls.current()).isEmpty();
        assertThat(arena.sawResultsOf("Best mob?")).isTrue();
    }

    @Test
    @DisplayName("a poll whose server never reports is dropped quietly, so polls are not blocked for good")
    void forgottenWithoutResult() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        smp.polls.start(smp.join("Tom"), "Best mob?", ANSWERS, TWO_MINUTES);
        settle();

        arena.timersFire();

        assertThat(arena.polls.current()).isEmpty();
        assertThat(arena.sawResultsOf("Best mob?")).isFalse();
    }

    @Test
    @DisplayName("a click when the owning server does not answer says so, and counts nothing")
    void ownerSilent() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        Player lilly = arena.join("Lilly");
        smp.polls.start(smp.join("Tom"), "Best mob?", ANSWERS, TWO_MINUTES);
        settle();
        smp.polls.stopNetwork();

        arena.buttons.get("Yes").accept(lilly.getUniqueId());
        for (int waited = 0; waited < 100 && arena.told.isEmpty(); waited++) {
            sleep();
        }

        assertThat(arena.told).containsExactly("Lilly: chat.poll.unreachable [answer, Yes]");
    }

    @Test
    @DisplayName("answers a player typed are shown as text on the other server, never as markup")
    void answersAreText() {
        Node smp = new Node("smp", true);
        Node arena = new Node("arena", true);
        smp.polls.start(smp.join("Tom"), "Colour?", List.of("<red>Red", "Blue"), TWO_MINUTES);
        settle();

        assertThat(arena.buttons).containsOnlyKeys("\\<red>Red", "Blue");
    }

    @Test
    @DisplayName("off a network nothing is sent and a poll works as it always did")
    void aloneIsAsBefore() {
        Node alone = new Node("smp", false);
        Node arena = new Node("arena", true);
        Player tom = alone.join("Tom");

        alone.polls.start(tom, "Best mob?", ANSWERS, TWO_MINUTES);
        alone.click(tom, "Yes");
        alone.timersFire();
        settle();

        assertThat(arena.polls.current()).isEmpty();
        assertThat(arena.buttons).isEmpty();
        assertThat(alone.told).containsExactly("Tom: chat.poll.voted [answer, Yes]");
        assertThat(alone.sawResultsOf("Best mob?")).isTrue();
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
