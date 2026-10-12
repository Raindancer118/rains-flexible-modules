package de.raindancer.modules.economy.service;

import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.DealerGame;
import de.raindancer.modules.economy.store.AccountBook;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** With dealers-only on, a game by command is refused — and the player is offered the way to the casino. */
class CasinoOfferTest {

    private final Messages messages = mock(Messages.class);
    private final ChatButtons buttons = mock(ChatButtons.class);
    private final EconomySettings settings = mock(EconomySettings.class);
    private final Server server = mock(Server.class);
    private final Player player = mock(Player.class);
    private final GamblingService service;

    CasinoOfferTest() {
        RainEconomy economy = mock(RainEconomy.class);
        when(economy.book()).thenReturn(mock(AccountBook.class));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        service = new GamblingService(mock(Plugin.class), server, economy, messages,
                mock(de.raindancer.core.ui.effect.Effects.class), buttons, Clock.systemUTC(),
                mock(GameSounds.class), settings);
        when(messages.prefixed(anyString(), any(Object[].class))).thenReturn(Component.text("offer"));
        when(buttons.ask(any(), any(Duration.class), any(), any())).thenReturn(Component.text("[yes][no]"));
    }

    @Test
    @DisplayName("refused at a dealer-only server: tells who deals it, then asks whether to go to the casino")
    void offersTheCasino() {
        when(settings.dealersOnly()).thenReturn(true);
        service.offersWarp(name -> name.equals("casino"));

        boolean refused = service.refuseRemote(player, DealerGame.SLOTS);

        assertThat(refused).isTrue();
        verify(messages).send(eq(player), eq("economy.gamble.dealers-only"), any(Object[].class));
        verify(messages).prefixed(eq("economy.gamble.go-to-casino"), any(Object[].class));
        verify(player).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("saying yes takes them to the casino warp")
    @SuppressWarnings("unchecked")
    void yesWarpsThem() {
        when(settings.dealersOnly()).thenReturn(true);
        service.offersWarp(name -> true);
        service.refuseRemote(player, DealerGame.SLOTS);

        ArgumentCaptor<Consumer<UUID>> yes = ArgumentCaptor.forClass(Consumer.class);
        verify(buttons).ask(eq(player.getUniqueId()), any(Duration.class), yes.capture(), any());
        when(server.getPlayer(any(UUID.class))).thenReturn(player);
        yes.getValue().accept(player.getUniqueId());

        verify(player).performCommand("warp casino");
    }

    @Test
    @DisplayName("no casino warp, no offer — the refusal stands alone")
    void noWarpNoOffer() {
        when(settings.dealersOnly()).thenReturn(true);
        service.offersWarp(name -> false);

        assertThat(service.refuseRemote(player, DealerGame.SLOTS)).isTrue();

        verify(buttons, never()).ask(any(), any(Duration.class), any(), any());
        verify(player, never()).sendMessage(any(Component.class));
    }

    @Test
    @DisplayName("games by command are not refused, and nothing is offered, when dealers-only is off")
    void dealersOnlyOff() {
        when(settings.dealersOnly()).thenReturn(false);
        service.offersWarp(name -> true);

        assertThat(service.refuseRemote(player, DealerGame.SLOTS)).isFalse();

        verify(messages, never()).send(any(Player.class), anyString(), any(Object[].class));
        verify(buttons, never()).ask(any(), any(Duration.class), any(), any());
    }
}
