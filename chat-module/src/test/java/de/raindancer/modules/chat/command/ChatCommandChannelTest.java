package de.raindancer.modules.chat.command;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatServices;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.service.FreezeService;
import de.raindancer.modules.chat.service.MentionService;
import de.raindancer.modules.chat.service.PrivateChatService;
import de.raindancer.modules.chat.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import de.raindancer.core.ui.chat.ChatChannel;
import de.raindancer.core.ui.chat.ChatChannels;
import org.junit.jupiter.api.AfterEach;
import org.bukkit.command.ConsoleCommandSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** /chat team, /chat all, and /chat on its own — the channel picker. */
class ChatCommandChannelTest {

    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private final ChatServices services = new ChatServices(null, server, null, null, messages, null, null,
            () -> ChatSettings.DEFAULTS, null, null, null, new FreezeService(), null, null,
            new PrivateChatService());
    private final List<Player> menusOpened = new ArrayList<>();
    private final ChatCommand command = new ChatCommand(() -> services);
    private final Player alice = mock(Player.class);
    private final UUID aliceId = UUID.randomUUID();

    private final ChatChannel team = new ChatChannel() {
        @Override
        public String id() {
            return "team";
        }

        @Override
        public String label() {
            return "Team";
        }

        @Override
        public Optional<Set<UUID>> audienceFor(UUID speaker) {
            return Optional.of(Set.of(speaker));
        }
    };

    ChatCommandChannelTest() {
        when(alice.getUniqueId()).thenReturn(aliceId);
        command.channelMenu((live, viewer) -> menusOpened.add(viewer));
    }

    @AfterEach
    void clean() {
        ChatChannels.unregister(team);
        ChatChannels.forget(aliceId);
    }

    private void run(org.bukkit.command.CommandSender who, String... args) {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(who);
        command.execute(source, args);
    }

    @Test
    @DisplayName("/chat team switches into team chat")
    void team() {
        ChatChannels.register(team);

        run(alice, "team");

        assertThat(ChatChannels.selected(aliceId)).isEqualTo("team");
        verify(messages).send(alice, "chat.channel.now", "channel", "Team");
    }

    @Test
    @DisplayName("/chat team without a team to talk to says so, and changes nothing")
    void noTeam() {
        run(alice, "team");

        assertThat(ChatChannels.selected(aliceId)).isEqualTo(ChatChannels.ALL);
        verify(messages).send(alice, "chat.channel.unavailable", "channel", "team");
    }

    @Test
    @DisplayName("/chat all goes back to everybody")
    void all() {
        ChatChannels.register(team);
        run(alice, "team");

        run(alice, "all");

        assertThat(ChatChannels.selected(aliceId)).isEqualTo(ChatChannels.ALL);
        verify(messages).send(alice, "chat.channel.now", "channel", "All");
    }

    @Test
    @DisplayName("/chat on its own opens the picker for a player, and still shows the usage to the console")
    void menu() {
        run(alice);
        assertThat(menusOpened).containsExactly(alice);

        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        run(console);
        verify(messages).send(eq(console), eq("chat.usage"), any(Object[].class));
    }

    @Test
    @DisplayName("team and all are offered to every player")
    void completes() {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(alice);

        assertThat(command.suggest(source, new String[]{""})).contains("team", "all");
    }
}
