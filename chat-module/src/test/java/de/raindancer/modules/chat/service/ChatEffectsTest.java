package de.raindancer.modules.chat.service;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatSettings;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatEffectsTest {

    private final Server server = mock(Server.class);
    private final Chat chat = mock(Chat.class);
    private final Messages messages = mock(Messages.class);
    private final FreezeService freeze = new FreezeService();
    private final ChatQualityService quality = new ChatQualityService(ChatSettings.DEFAULTS);
    private final FormatService format = mock(FormatService.class);
    private final List<Collection<UUID>> cues = new ArrayList<>();
    private final ChatEffects effects = new ChatEffects(server, chat, messages, freeze, quality, format, cues::add);

    @Test
    @DisplayName("freeze and slowmode are set to the state asked for, and read back as the state")
    void setsAndReportsState() {
        effects.freeze(true, "Tom");
        effects.slowmode(12, "Tom");

        assertThat(freeze.isFrozen()).isTrue();
        assertThat(effects.state()).isEqualTo(new ChatNetwork.State(true, 12));

        effects.freeze(false, "Tom");
        effects.slowmode(-1, "Tom");

        assertThat(freeze.isFrozen()).isFalse();
        assertThat(quality.isSlowmodeOverridden()).isFalse();
        assertThat(effects.state()).isEqualTo(new ChatNetwork.State(false, -1));
    }

    @Test
    @DisplayName("freezing twice is still frozen: a state, not a toggle")
    void notAToggle() {
        effects.freeze(true, "Tom");
        effects.freeze(true, "Lilly");

        assertThat(freeze.isFrozen()).isTrue();
    }

    @Test
    @DisplayName("adopting a state changes it without telling the players")
    void adoptIsSilent() {
        effects.adopt(new ChatNetwork.State(true, 30));

        assertThat(effects.state()).isEqualTo(new ChatNetwork.State(true, 30));
        verify(chat, times(0)).broadcast(any(String.class), any());
    }

    @Test
    @DisplayName("clearing blanks every player's chat and says who did it")
    void clearBlanksEverybody() {
        Player a = mock(Player.class);
        Player b = mock(Player.class);
        doReturn(List.of(a, b)).when(server).getOnlinePlayers();

        effects.clear("Tom");

        verify(chat, times(100)).blank(a);
        verify(chat, times(100)).blank(b);
    }

    @Test
    @DisplayName("a banner and an ad play the notify cue for everybody online")
    void cuesEverybody() {
        Player a = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(a.getUniqueId()).thenReturn(id);
        doReturn(List.of(a)).when(server).getOnlinePlayers();

        effects.banner("hi");
        effects.ad(UUID.randomUUID(), "Lilly", "cows");

        assertThat(cues).hasSize(2).allSatisfy(who -> assertThat(who).containsExactly(id));
        verify(format).renderAd(any(UUID.class), any(String.class), any(String.class));
    }
}
