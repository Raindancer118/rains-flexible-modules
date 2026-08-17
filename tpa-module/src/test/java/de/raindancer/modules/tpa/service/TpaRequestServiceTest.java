package de.raindancer.modules.tpa.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.tpa.TpaSettings;
import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.rules.TpaAskingRule;
import de.raindancer.modules.tpa.store.TpaRequests;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Whether asking somebody who is vanished gives their presence away. It must not: {@link #ask} has to
 * refuse before any of the ordinary asking rules ever run, and refuse with the same wording used for
 * somebody who is not online at all.
 */
class TpaRequestServiceTest {

    private final Plugin plugin = mock(Plugin.class);
    private final TpaRequests requests = mock(TpaRequests.class);
    private final TpaPrefsService prefs = mock(TpaPrefsService.class);
    private final TpaAskingRule rule = mock(TpaAskingRule.class);
    private final Travel travel = mock(Travel.class);
    private final Messages messages = mock(Messages.class);
    private final ChatButtons buttons = mock(ChatButtons.class);
    private final Vanish vanish = new Vanish(mock(VanishSink.class));

    private final TpaRequestService service = new TpaRequestService(plugin, requests, prefs, rule,
            travel, messages, buttons, vanish, TpaSettings.DEFAULTS);

    private Player player(String name) {
        Player who = mock(Player.class);
        when(who.getUniqueId()).thenReturn(UUID.randomUUID());
        when(who.getName()).thenReturn(name);
        return who;
    }

    @Test
    @DisplayName("asking a vanished player is refused as if they were not online, before any rule runs")
    void vanishedTargetIsRefusedLikeNobodyIsThere() {
        Player from = player("Steve");
        Player to = player("Ghost");
        vanish.vanish(to.getUniqueId());

        boolean asked = service.ask(from, to, TpaKind.TO);

        assertThat(asked).isFalse();
        verify(messages).send(from, "tpa.no-such-player", "player", to.getName());
        verifyNoInteractions(rule);
        verify(requests, never()).ask(any(), any(), any());
    }

    @Test
    @DisplayName("staff who may see vanished players can still ask one")
    void staffCanStillAskAVanishedTarget() {
        Player from = player("Mod");
        Player to = player("Ghost");
        vanish.vanish(to.getUniqueId());
        vanish.maySeeVanished(from.getUniqueId(), true);
        when(rule.check(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(TpaAskingRule.Verdict.TOO_SOON);

        service.ask(from, to, TpaKind.TO);

        verify(messages, never()).send(from, "tpa.no-such-player", "player", to.getName());
        verify(rule).check(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    @DisplayName("asking somebody who is not vanished is unaffected")
    void ordinaryAskingIsUntouched() {
        Player from = player("Steve");
        Player to = player("Alex");
        when(rule.check(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(TpaAskingRule.Verdict.TOO_SOON);

        service.ask(from, to, TpaKind.TO);

        verify(messages, never()).send(from, "tpa.no-such-player", "player", to.getName());
        verify(rule).check(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
    }
}
