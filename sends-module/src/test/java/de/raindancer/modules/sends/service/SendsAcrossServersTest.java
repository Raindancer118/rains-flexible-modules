package de.raindancer.modules.sends.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.sends.SendsSettings;
import de.raindancer.modules.sends.model.Send;
import de.raindancer.modules.sends.rules.SendableItemsRule;
import de.raindancer.modules.sends.store.MemorySendStorage;
import de.raindancer.modules.sends.store.SendBook;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static de.raindancer.modules.sends.service.SendServiceTest.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two servers over one database: Alice is on one, Bob on the other. The one thing that must hold is that the
 * items end up in exactly one place, whoever gets to a send first.
 */
class SendsAcrossServersTest {

    private final MemorySendStorage database = new MemorySendStorage();
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Map<UUID, String> presence = new ConcurrentHashMap<>();

    private final class Side {
        final String id;
        final Server server = mock(Server.class);
        final Messages messages = mock(Messages.class);
        final SendBook book = new SendBook(database, Runnable::run, now::get);
        final SendService service;
        Side other;
        /** Hears nothing from the bus, so it keeps the picture it had. */
        boolean deaf;

        Side(String id) {
            this.id = id;
            Plugin plugin = mock(Plugin.class);
            when(plugin.isEnabled()).thenReturn(false);
            service = new SendService(plugin, server, Log.of("test"), messages, mock(Effects.class), book,
                    new SendableItemsRule(stack -> false), now::get, SendsSettings.DEFAULTS.withCooldownSeconds(0));
            service.connectNetwork(new SendService.Network() {
                @Override
                public Optional<String> onlineElsewhere(UUID player) {
                    return Optional.ofNullable(presence.get(player)).filter(server -> !server.equals(id))
                            .map(server -> "somebody");
                }

                @Override
                public boolean tell(UUID player, String key, String... values) {
                    org.bukkit.entity.Player there = other.server.getPlayer(player);
                    if (there == null) {
                        return false;
                    }
                    other.messages.send(there, key, (Object[]) values);
                    return true;
                }

                @Override
                public void changed(UUID send) {
                    if (!other.deaf) {
                        other.service.changedElsewhere(send);
                    }
                }
            });
        }

        SendServiceTest.Person join(String name) {
            SendServiceTest.Person person = new SendServiceTest.Person(name);
            when(server.getPlayer(person.id)).thenReturn(person.player);
            presence.put(person.id, id);
            return person;
        }
    }

    private Side smp;
    private Side arena;
    private SendServiceTest.Person alice;
    private SendServiceTest.Person bob;

    @BeforeEach
    void servers() {
        smp = new Side("smp");
        arena = new Side("arena");
        smp.other = arena;
        arena.other = smp;
        alice = smp.join("Alice");
        bob = arena.join("Bob");
    }

    private Send sent() {
        assertThat(smp.service.create(alice.player, bob.id, List.of(item("diamond", 3)))).isTrue();
        return smp.book.outgoing(alice.id).getFirst();
    }

    @Test
    @DisplayName("a recipient on another server is told, sees the send there, and accepting hands the items over once")
    void acceptedOnTheOtherServer() {
        Send send = sent();

        verify(arena.messages).send(eq(bob.player), eq("sends.incoming"), any(Object[].class));
        assertThat(arena.book.incoming(bob.id)).extracting(Send::id).as("learnt over the network")
                .containsExactly(send.id());

        arena.service.accept(bob.player, send.id());

        assertThat(bob.received).hasSize(1);
        assertThat(alice.received).isEmpty();
        verify(smp.messages).send(eq(alice.player), eq("sends.accepted"), any(Object[].class));
        arena.service.accept(bob.player, send.id());
        assertThat(bob.received).as("a second accept finds nothing").hasSize(1);
    }

    @Test
    @DisplayName("the recipient accepts first: a cancel from the sender's server, still holding the old picture, loses")
    void acceptBeatsCancel() {
        Send send = sent();
        smp.deaf = true;

        arena.service.accept(bob.player, send.id());
        smp.service.release(alice.player, send.id());

        assertThat(bob.received).hasSize(1);
        assertThat(alice.received).as("the items are not given back as well").isEmpty();
        verify(smp.messages).send(eq(alice.player), eq("sends.gone"));
    }

    @Test
    @DisplayName("the sender cancels first: an accept from the recipient's server, still holding the old picture, loses")
    void cancelBeatsAccept() {
        Send send = sent();
        arena.deaf = true;

        smp.service.release(alice.player, send.id());
        arena.service.accept(bob.player, send.id());

        assertThat(bob.received).as("nothing is handed to the recipient").isEmpty();
        assertThat(alice.received).hasSize(1);
        verify(arena.messages).send(eq(bob.player), eq("sends.gone"));
    }

    @Test
    @DisplayName("declining on one server returns the items to the sender on the other, who collects them")
    void declineReturnsAcrossServers() {
        Send send = sent();

        arena.service.release(bob.player, send.id());

        assertThat(bob.received).isEmpty();
        assertThat(alice.received).hasSize(1);
        verify(smp.messages).send(eq(alice.player), eq("sends.declined"), any(Object[].class));
    }

    @Test
    @DisplayName("somebody who is online on no server cannot be sent to")
    void nobodyThere() {
        presence.remove(bob.id);
        when(arena.server.getPlayer(bob.id)).thenReturn(null);

        assertThat(smp.service.create(alice.player, bob.id, List.of(item("diamond", 1)))).isFalse();
        verify(smp.messages).send(eq(alice.player), eq("sends.refuse.offline"), any(Object[].class));
    }

    @Test
    @DisplayName("two servers sweeping the same run-out send return it once")
    void sweepingTwiceReturnsOnce() {
        Send send = sent();
        now.addAndGet(11 * 60_000);

        smp.service.sweep();
        arena.service.sweep();

        assertThat(alice.received).hasSize(1);
        assertThat(bob.received).isEmpty();
    }
}
