package de.raindancer.modules.tpa.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.modules.tpa.TpaSettings;
import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.model.TpaPrefs;
import de.raindancer.modules.tpa.model.TpaRequest;
import de.raindancer.modules.tpa.rules.TpaAskingRule;
import de.raindancer.modules.tpa.store.TpaRequests;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TpaAutoAcceptAndEveryoneTest {

    private final Plugin plugin = mock(Plugin.class);
    private final TpaRequests requests = new TpaRequests();
    private final TpaPrefsService prefs = mock(TpaPrefsService.class);
    private final TpaAskingRule rule = mock(TpaAskingRule.class);
    private final Travel travel = mock(Travel.class);
    private final Messages messages = mock(Messages.class);
    private final ChatButtons buttons = mock(ChatButtons.class);
    private final Vanish vanish = new Vanish(mock(VanishSink.class));
    private final Server server = mock(Server.class);
    private final World world = mock(World.class);

    private final TpaRequestService service = new TpaRequestService(plugin, requests, prefs, rule,
            travel, messages, buttons, vanish, TpaSettings.DEFAULTS);

    private Player player(String name) {
        Player who = mock(Player.class);
        when(who.getUniqueId()).thenReturn(UUID.randomUUID());
        when(who.getName()).thenReturn(name);
        when(who.isOnline()).thenReturn(true);
        when(who.getServer()).thenReturn(server);
        when(who.getWorld()).thenReturn(world);
        return who;
    }

    private void everythingIsFine() {
        when(plugin.getServer()).thenReturn(server);
        when(rule.check(any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(TpaAskingRule.Verdict.FINE);
        when(prefs.of(any())).thenReturn(TpaPrefs.untouched());
        when(buttons.ask(any(), any(Duration.class), any(), any())).thenReturn(Component.text("[Accept] [Deny]"));
        when(messages.prefixed(any(), any(Object[].class))).thenReturn(Component.text("asked"));
    }

    @Test
    @DisplayName("somebody on auto-accept is not shown buttons: the request is answered the moment it is made")
    void autoAcceptAnswersAtOnce() {
        everythingIsFine();
        Player from = player("Steve");
        Player to = player("Alex");
        when(prefs.of(to.getUniqueId())).thenReturn(TpaPrefs.untouched().autoAccepting(true));
        when(server.getPlayer(any(UUID.class))).thenReturn(null);

        boolean asked = service.ask(from, to, TpaKind.HERE);

        assertThat(asked).isTrue();
        verify(messages).send(to, "tpa.auto-accepted", "player", "Steve");
        verify(buttons, never()).ask(any(), any(Duration.class), any(), any());
        assertThat(requests.has(from.getUniqueId(), to.getUniqueId()))
                .as("accepting takes the request out of the store").isFalse();
    }

    @Test
    @DisplayName("everybody else still gets the [Accept] [Deny] question")
    void othersAreStillAsked() {
        everythingIsFine();
        Player from = player("Steve");
        Player to = player("Alex");

        service.ask(from, to, TpaKind.HERE);

        verify(buttons).ask(eq(to.getUniqueId()), any(Duration.class), any(), any());
        verify(messages, never()).send(eq(to), eq("tpa.auto-accepted"), any(Object[].class));
        assertThat(requests.has(from.getUniqueId(), to.getUniqueId())).isTrue();
    }

    @Test
    @DisplayName("/tpahere @a asks everybody online who can be asked, once each, and counts who was passed over")
    void everyoneIsAsked() {
        everythingIsFine();
        Player op = player("Op");
        Player bob = player("Bob");
        Player carol = player("Carol");
        Player dan = player("Dan");
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(op, bob, carol, dan));
        when(server.getPlayer(bob.getUniqueId())).thenReturn(bob);
        when(server.getPlayer(carol.getUniqueId())).thenReturn(carol);
        when(server.getPlayer(dan.getUniqueId())).thenReturn(dan);
        UUID opId = op.getUniqueId();
        UUID danId = dan.getUniqueId();
        when(rule.check(eq(opId), eq(danId), any(), anyBoolean(), anyBoolean(), anyBoolean(),
                anyBoolean())).thenReturn(TpaAskingRule.Verdict.NOT_ACCEPTING);

        int asked = service.askEveryone(op);

        assertThat(asked).isEqualTo(2);
        assertThat(requests.allFrom(op.getUniqueId())).extracting(TpaRequest::to)
                .containsExactlyInAnyOrder(bob.getUniqueId(), carol.getUniqueId());
        assertThat(requests.allFrom(op.getUniqueId())).allMatch(request -> request.kind() == TpaKind.HERE);
        List<Object> said = org.mockito.Mockito.mockingDetails(messages).getInvocations().stream()
                .filter(call -> call.getArguments().length > 1 && "tpa.asked-everyone".equals(call.getArguments()[1]))
                .flatMap(call -> java.util.Arrays.stream(call.getArguments()))
                .toList();
        assertThat(said).contains("count", 2, "passed", 1, "seconds");
        verify(buttons).ask(eq(bob.getUniqueId()), any(Duration.class), any(), any());
        verify(buttons).ask(eq(carol.getUniqueId()), any(Duration.class), any(), any());
        verify(buttons, never()).ask(eq(dan.getUniqueId()), any(Duration.class), any(), any());
    }

    @Test
    @DisplayName("nobody to ask: says so and makes nothing")
    void nobodyToAsk() {
        everythingIsFine();
        Player op = player("Op");
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(op));

        assertThat(service.askEveryone(op)).isZero();

        verify(messages).send(op, "tpa.nobody-to-ask");
        assertThat(requests.count()).isZero();
    }

    @Test
    @DisplayName("a vanished player is not asked, and not counted either")
    void vanishedAreLeftOut() {
        everythingIsFine();
        Player op = player("Op");
        Player ghost = player("Ghost");
        vanish.vanish(ghost.getUniqueId());
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(op, ghost));

        assertThat(service.askEveryone(op)).isZero();

        assertThat(requests.has(op.getUniqueId(), ghost.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("cancelling takes all of a batch back and tells each person")
    void cancellingTheBatch() {
        everythingIsFine();
        Player op = player("Op");
        Player bob = player("Bob");
        Player carol = player("Carol");
        when(server.getOnlinePlayers()).thenAnswer(call -> List.of(op, bob, carol));
        when(server.getPlayer(bob.getUniqueId())).thenReturn(bob);
        when(server.getPlayer(carol.getUniqueId())).thenReturn(carol);
        when(travel.isTravelling(any())).thenReturn(false);
        service.askEveryone(op);

        assertThat(service.cancel(op)).isTrue();

        assertThat(requests.count()).isZero();
        verify(messages).send(op, "tpa.took-them-back", "count", 2);
    }
}
