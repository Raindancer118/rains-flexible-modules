package de.raindancer.modules.essentials.service;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Vanishing reads as this module's own quit line, to exactly the players Core says should see one. */
class WelcomeServiceTest {

    private final Messages messages = mock(Messages.class);
    private final Chat chat = mock(Chat.class);
    private final Player ada = mock(Player.class);

    @Test
    @DisplayName("with its own join and quit lines on, a vanish says its quit line and vanilla says nothing")
    void ownLines() {
        when(messages.raw("essentials.welcome.quit")).thenReturn("<gray>- <white><player></white> left.");
        when(ada.getName()).thenReturn("Ada");
        WelcomeService welcome = new WelcomeService(messages, chat, mock(ReactionService.class), EssentialsSettings.DEFAULTS);
        List<Player> to = List.of(mock(Player.class));

        assertThat(welcome.seemsToLeave(ada, to)).isTrue();
        verify(chat).broadcast(eq(to), eq("<gray>- <white><player></white> left."), any());
    }

    @Test
    @DisplayName("with them off, it leaves the line to vanilla")
    void vanillaLines() {
        EssentialsSettings off = new EssentialsSettings(3, true, 300, true, false, true, true, 16, true, true,
                List.of(), true, List.of(), true, true, 20, true, true, org.bukkit.GameMode.SURVIVAL, true, false,
                true, true, true, true);
        WelcomeService welcome = new WelcomeService(messages, chat, mock(ReactionService.class), off);

        assertThat(welcome.seemsToLeave(ada, List.of())).isFalse();
        assertThat(welcome.seemsToArrive(ada, List.of())).isFalse();
        verify(chat, never()).broadcast(any(java.util.Collection.class), anyString(), any());
    }
}
