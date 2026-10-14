package de.raindancer.modules.essentials.profile;

import de.raindancer.core.RainsCore;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.service.MessagingService;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The profile's "Message" button is offered for, and reaches, somebody on another server too. */
class MessageProfileExtensionTest {

    private final EssentialsServices services = mock(EssentialsServices.class);
    private final Server server = mock(Server.class);
    private final MessagingService messaging = mock(MessagingService.class);
    private final Messages messages = mock(Messages.class);
    private final Player viewer = mock(Player.class);
    private final OfflinePlayer subject = mock(OfflinePlayer.class);
    private final UUID subjectId = UUID.randomUUID();
    private final UUID viewerId = UUID.randomUUID();
    private final MessageProfileExtension extension = new MessageProfileExtension(services);

    @BeforeEach
    void wire() {
        when(viewer.getUniqueId()).thenReturn(viewerId);
        when(subject.getUniqueId()).thenReturn(subjectId);
        when(subject.getName()).thenReturn("Alex");
        when(services.server()).thenReturn(server);
        when(services.messaging()).thenReturn(messaging);
        when(services.messages()).thenReturn(messages);
        when(services.plugin()).thenReturn(mock(Plugin.class));
        when(services.core()).thenReturn(mock(RainsCore.class));
        when(messaging.findElsewhere(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("somebody online here is offered by the name they have here")
    void onlineHere() {
        Player alex = mock(Player.class);
        when(alex.getName()).thenReturn("Alex");
        when(server.getPlayer(subjectId)).thenReturn(alex);

        assertThat(extension.reachable(viewer, subject)).contains("Alex");
    }

    @Test
    @DisplayName("somebody visible on another server is offered too; somebody nowhere, or yourself, is not")
    void elsewhereOrNowhere() {
        assertThat(extension.reachable(viewer, subject)).isEmpty();

        when(messaging.findElsewhere(any(), any())).thenReturn(Optional.of("Alex"));
        assertThat(extension.reachable(viewer, subject)).contains("Alex");

        when(subject.getUniqueId()).thenReturn(viewerId);
        assertThat(extension.reachable(viewer, subject)).isEmpty();
    }

    @Test
    @DisplayName("the typed message goes across when they are on another server by now, and says so when they are gone")
    void answeredElsewhere() {
        when(messaging.findElsewhere(any(), any())).thenReturn(Optional.of("Alex"));
        extension.onAnswered(viewer, subjectId, "Alex", "hello");
        verify(messaging).sendAcross(viewer, "Alex", "hello");

        when(messaging.findElsewhere(any(), any())).thenReturn(Optional.empty());
        extension.onAnswered(viewer, subjectId, "Alex", "hello again");
        verify(messages).send(viewer, "essentials.no-such-player", "player", "Alex");
        verify(messaging, never()).send(any(), any(), any());
    }

    @Test
    @DisplayName("if they came to this server meanwhile it is an ordinary /msg")
    void answeredHere() {
        Player alex = mock(Player.class);
        when(server.getPlayer(subjectId)).thenReturn(alex);

        extension.onAnswered(viewer, subjectId, "Alex", "hello");

        verify(messaging).send(viewer, alex, "hello");
    }
}
