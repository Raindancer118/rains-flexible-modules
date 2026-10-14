package de.raindancer.modules.jobs.service;

import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.ui.messages.Messages;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NoticesTest {

    @Test
    @DisplayName("an announcement is said on this server and on every other, with its placeholders")
    void everybodyOnTheNetwork() {
        LocalBus.Hub hub = new LocalBus.Hub();
        Player onSmp = mock(Player.class);
        Player onArena = mock(Player.class);
        Server smp = mock(Server.class);
        Server arena = mock(Server.class);
        when(smp.getOnlinePlayers()).thenAnswer(call -> List.of(onSmp));
        when(arena.getOnlinePlayers()).thenAnswer(call -> List.of(onArena));
        Messages smpMessages = mock(Messages.class);
        Messages arenaMessages = mock(Messages.class);
        Notices here = new Notices(smp, smpMessages);
        Notices there = new Notices(arena, arenaMessages);
        here.network(hub.bus("smp"), java.util.Optional::empty);
        there.network(hub.bus("arena"), java.util.Optional::empty);

        here.everybody("jobs.reached", "goal", "Cod", "players", 3);
        hub.drain();

        verify(smpMessages).send(onSmp, "jobs.reached", "goal", "Cod", "players", 3);
        verify(arenaMessages).send(onArena, "jobs.reached", "goal", "Cod", "players", "3");
    }

    @Test
    @DisplayName("somebody on this server is told here; somebody nowhere is told nothing")
    void tell() {
        Player player = mock(Player.class);
        UUID ana = UUID.randomUUID();
        Server server = mock(Server.class);
        when(server.getPlayer(ana)).thenReturn(player);
        Messages messages = mock(Messages.class);
        Notices notices = new Notices(server, messages);
        notices.tell(ana, "jobs.paid", "amount", "5");
        verify(messages).send(player, "jobs.paid", "amount", "5");
        notices.tell(UUID.randomUUID(), "jobs.paid");
        org.mockito.Mockito.verifyNoMoreInteractions(messages);
    }
}
