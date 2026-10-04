package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.testkit.TestPlayers;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.Parsers;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Typed values — a run's time, a seed, a pool of seeds — read by Core's parsers and asked for through
 * Core's anvil window (from a menu) or chat question (from a command).
 */
class SpeedrunInputTest {

    @Nested
    @DisplayName("what is accepted")
    class Parsing {

        @Test
        @DisplayName("a time: a clock reading, Core's lengths, or 0 — anything else is refused with what works")
        void time() {
            assertThat(SpeedrunInput.TIME.parse("42:05").value()).isEqualTo(Duration.ofMinutes(42).plusSeconds(5));
            assertThat(SpeedrunInput.TIME.parse("1:02:03").value()).isEqualTo(Duration.ofSeconds(3723));
            assertThat(SpeedrunInput.TIME.parse("1h30m").value()).isEqualTo(Duration.ofMinutes(90));
            assertThat(SpeedrunInput.TIME.parse(" 0 ").value()).isEqualTo(Duration.ZERO);
            assertThat(SpeedrunInput.TIME.parse("soon").isOk()).isFalse();
            assertThat(SpeedrunInput.TIME.parse("soon").problem()).contains("42:05");
            assertThat(SpeedrunInput.TIME.parse("12:75").isOk()).isFalse();
        }

        @Test
        @DisplayName("a seed: a number or a word, never blank, never 'random', never a novel")
        void seed() {
            assertThat(SpeedrunInput.SEED.parse("-4172144997902289642").value()).isEqualTo("-4172144997902289642");
            assertThat(SpeedrunInput.SEED.parse(" glacier ").value()).isEqualTo("glacier");
            assertThat(SpeedrunInput.SEED.parse("").isOk()).isFalse();
            assertThat(SpeedrunInput.SEED.parse("random").isOk()).isFalse();
            assertThat(SpeedrunInput.SEED.parse("x".repeat(65)).isOk()).isFalse();
        }

        @Test
        @DisplayName("a pool: seeds separated by commas — at least one")
        void pool() {
            assertThat(SpeedrunInput.SEED_POOL.parse("1, 2;3").value()).isEqualTo("1, 2;3");
            assertThat(SpeedrunInput.SEED_POOL.parse(" ,, ").isOk()).isFalse();
        }
    }

    /** Records what was asked, and answers when the test says so. */
    static final class FakeInput implements SpeedrunInput {
        final List<String> windows = new ArrayList<>();
        final List<String> chats = new ArrayList<>();
        final AtomicReference<Consumer<String>> answer = new AtomicReference<>();

        @Override
        public <T> void window(Player player, String title, String start, Parsers.Parser<T> parser, Consumer<T> then) {
            windows.add(title);
            answer.set(typed -> then.accept(parser.parse(typed).value()));
        }

        @Override
        public <T> void chat(Player player, String prompt, Parsers.Parser<T> parser, List<String> suggestions,
                             Consumer<T> then) {
            chats.add(prompt);
            answer.set(typed -> then.accept(parser.parse(typed).value()));
        }

        void type(String typed) {
            answer.get().accept(typed);
        }
    }

    @Nested
    @DisplayName("asking")
    class Asking {

        @TempDir
        Path folder;

        private SpeedrunLobby lobby;
        private Messages messages;
        private FakeInput input;
        private SettingsStore<SpeedrunSettings> settings;

        @BeforeEach
        void setUp() {
            JavaPlugin plugin = mock(JavaPlugin.class);
            settings = new SettingsStore<>(SettingsSchema.of(SpeedrunSettings.class, SpeedrunSettings.DEFAULTS),
                    folder.resolve("speedrun.yml"));
            settings.load();
            lobby = new SpeedrunLobby(plugin, settings);
            messages = mock(Messages.class);
            when(messages.get(anyString(), any(Object[].class))).thenAnswer(call -> Component.text("Q " + call.getArgument(0)));
            when(messages.get(anyString())).thenAnswer(call -> Component.text("Q " + call.getArgument(0)));
            input = new FakeInput();
            lobby.useInput(input);
        }

        @Test
        @DisplayName("a time from a menu is asked in a window titled with the question, and handed over parsed")
        void timeInAWindow() {
            Player admin = TestPlayers.player("Admin", PermissionNodes.ADMIN);
            List<Duration> got = new ArrayList<>();

            new SpeedrunActions(lobby, messages).askTime(admin, "speedrun.resume.ask", got::add);
            input.type("42:05");

            assertThat(input.windows).containsExactly("Q speedrun.resume.ask");
            assertThat(got).containsExactly(Duration.ofMinutes(42).plusSeconds(5));
        }

        @Test
        @DisplayName("a typed seed from the seed page becomes the fixed seed — still only for somebody with the node")
        void seedInAWindow() {
            Player admin = TestPlayers.player("Admin", PermissionNodes.ADMIN);
            new SpeedrunActions(lobby, messages).askSeed(admin, () -> { });

            input.type("glacier");

            assertThat(lobby.config().seed()).isEqualTo("glacier");
            assertThat(lobby.config().seedMode()).isEqualTo(SpeedrunSeedMode.FIXED);
        }

        @Test
        @DisplayName("…and lost the node while typing: nothing changes")
        void seedRefusedOnAnswer() {
            Player player = TestPlayers.player("Former");
            new SpeedrunActions(lobby, messages).askSeed(player, () -> { });

            input.type("glacier");

            assertThat(lobby.config().seed()).isNotEqualTo("glacier");
        }

        @Test
        @DisplayName("a pool typed on the seed page becomes the pool")
        void poolInAWindow() {
            Player admin = TestPlayers.player("Admin", PermissionNodes.ADMIN);
            new SpeedrunActions(lobby, messages).askSeedPool(admin, () -> { });

            input.type("1, 2, 3");

            assertThat(lobby.config().seedPool()).isEqualTo("1, 2, 3");
            assertThat(lobby.config().seedMode()).isEqualTo(SpeedrunSeedMode.POOL);
        }

        @Test
        @DisplayName("/speedrun time with no time asks in chat — the console gets the usage instead")
        void timeFromACommand() {
            SpeedrunJoinCommand command = new SpeedrunJoinCommand(() -> new SpeedrunAdminServices(lobby, messages));
            Player admin = TestPlayers.player("Admin", PermissionNodes.ADMIN);
            CommandSourceStack fromAdmin = mock(CommandSourceStack.class);
            when(fromAdmin.getSender()).thenReturn(admin);

            command.execute(fromAdmin, new String[]{"time"});

            assertThat(input.chats).containsExactly("Q speedrun.time.ask");

            CommandSender console = mock(CommandSender.class);
            when(console.hasPermission(anyString())).thenReturn(true);
            CommandSourceStack fromConsole = mock(CommandSourceStack.class);
            when(fromConsole.getSender()).thenReturn(console);
            command.execute(fromConsole, new String[]{"time"});
            verify(messages).send(console, "speedrun.time.usage");
        }
    }
}
