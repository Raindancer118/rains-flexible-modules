package de.raindancer.modules.essentials.service;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.store.EssentialsStore;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagingServiceTest {

    private final EssentialsStore store = new EssentialsStore(Path.of("target", "test-essentials"));
    private final Messages messages = mock(Messages.class);
    private final Chat chat = mock(Chat.class);
    private final MessagingService service =
            new MessagingService(store, messages, chat, EssentialsSettings.DEFAULTS);

    private Player player(String name) {
        Player who = mock(Player.class);
        when(who.getUniqueId()).thenReturn(UUID.randomUUID());
        when(who.getName()).thenReturn(name);
        return who;
    }

    @Nested
    @DisplayName("sending")
    class Sending {

        @Test
        @DisplayName("delivers to both, and remembers each as the other's reply target")
        void delivers() {
            Player from = player("Tom");
            Player to = player("Alex");

            boolean sent = service.send(from, to, "hi");

            assertThat(sent).isTrue();
            assertThat(service.replyTarget(to.getUniqueId())).contains(from.getUniqueId());
            assertThat(service.replyTarget(from.getUniqueId())).contains(to.getUniqueId());
        }

        @Test
        @DisplayName("refuses a message to yourself")
        void refusesYourself() {
            Player who = player("Tom");

            boolean sent = service.send(who, who, "hi");

            assertThat(sent).isFalse();
        }

        @Test
        @DisplayName("an ignored sender's message never arrives")
        void ignoredNeverArrives() {
            Player from = player("Tom");
            Player to = player("Alex");
            service.ignore(to, from.getUniqueId());

            boolean sent = service.send(from, to, "hi");

            assertThat(sent).isFalse();
        }
    }

    @Nested
    @DisplayName("ignoring")
    class Ignoring {

        @Test
        @DisplayName("blocking, then unblocking, leaves nobody ignored")
        void blockThenUnblock() {
            Player who = player("Tom");
            UUID target = UUID.randomUUID();

            assertThat(service.ignore(who, target)).isTrue();
            assertThat(service.isIgnoring(who.getUniqueId(), target)).isTrue();

            assertThat(service.stopIgnoring(who, target)).isTrue();
            assertThat(service.isIgnoring(who.getUniqueId(), target)).isFalse();
        }

        @Test
        @DisplayName("blocking twice changes nothing the second time")
        void blockingTwiceIsNotAChange() {
            Player who = player("Tom");
            UUID target = UUID.randomUUID();

            assertThat(service.ignore(who, target)).isTrue();
            assertThat(service.ignore(who, target)).isFalse();
        }
    }

    @Test
    @DisplayName("forgetting a player drops reply-target state without touching who they ignore")
    void forgetDropsSessionStateOnly() {
        Player from = player("Tom");
        Player to = player("Alex");
        service.send(from, to, "hi");
        service.ignore(to, from.getUniqueId());

        service.forget(from.getUniqueId());

        assertThat(service.replyTarget(from.getUniqueId())).isEmpty();
        assertThat(service.isIgnoring(to.getUniqueId(), from.getUniqueId())).isTrue();
    }
}
