package de.raindancer.modules.essentials.listener;

import de.raindancer.core.RainsCore;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.service.AdminModeService;
import de.raindancer.modules.essentials.service.AfkService;
import de.raindancer.modules.essentials.service.NicknameService;
import de.raindancer.modules.essentials.service.RulesService;
import de.raindancer.modules.essentials.service.WelcomeService;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The welcome and the rules go to somebody new to the whole network, once — not to everybody new to a server. */
class EssentialsSessionListenerTest {

    private final EssentialsServices services = mock(EssentialsServices.class);
    private final RainsCore core = mock(RainsCore.class);
    private final Vanish vanish = mock(Vanish.class);
    private final WelcomeService welcome = mock(WelcomeService.class);
    private final RulesService rules = mock(RulesService.class);
    private final Player player = mock(Player.class);
    private final PlayerJoinEvent event = mock(PlayerJoinEvent.class);
    private final CompletableFuture<Boolean> isNew = new CompletableFuture<>();

    @BeforeEach
    void wire() {
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPlayedBefore()).thenReturn(false);
        when(event.getPlayer()).thenReturn(player);
        when(services.plugin()).thenReturn(mock(Plugin.class));
        when(services.core()).thenReturn(core);
        when(core.vanish()).thenReturn(vanish);
        when(core.isNewPlayer(player)).thenReturn(isNew);
        when(services.welcome()).thenReturn(welcome);
        when(services.rules()).thenReturn(rules);
        when(services.afk()).thenReturn(mock(AfkService.class));
        when(services.nicknames()).thenReturn(mock(NicknameService.class));
        when(services.adminMode()).thenReturn(mock(AdminModeService.class));
    }

    @Test
    @DisplayName("nothing is welcomed until the network has said whether they are new")
    void waitsForTheNetwork() {
        new EssentialsSessionListener(services).onJoin(event);

        verify(welcome, never()).joined(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(rules, never()).firstJoin(any());
    }

    @Test
    @DisplayName("new to the network: the first-join welcome and the rules")
    void newToTheNetwork() {
        new EssentialsSessionListener(services).onJoin(event);
        isNew.complete(true);

        verify(event).joinMessage(null);
        verify(welcome).joined(player, true);
        verify(rules).firstJoin(player);
    }

    @Test
    @DisplayName("new to this server but played elsewhere: the ordinary welcome, no rules")
    void newToTheServerOnly() {
        new EssentialsSessionListener(services).onJoin(event);
        isNew.complete(false);

        verify(welcome).joined(player, false);
        verify(rules, never()).firstJoin(any());
    }

    @Test
    @DisplayName("gone by the time the answer comes: nobody is welcomed")
    void leftMeanwhile() {
        new EssentialsSessionListener(services).onJoin(event);
        when(player.isOnline()).thenReturn(false);
        isNew.complete(true);

        verify(welcome, never()).joined(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(rules, never()).firstJoin(any());
    }

    @Test
    @DisplayName("a vanished newcomer is not announced, but still gets the rules")
    void vanished() {
        when(vanish.isVanished(player.getUniqueId())).thenReturn(true);
        new EssentialsSessionListener(services).onJoin(event);
        isNew.complete(true);

        verify(welcome, never()).joined(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(rules).firstJoin(player);
    }

    @Test
    @DisplayName("a network that cannot say falls back to this server's own record")
    void failureFallsBack() {
        new EssentialsSessionListener(services).onJoin(event);
        isNew.completeExceptionally(new IllegalStateException("database down"));

        verify(welcome).joined(player, true);
    }
}
