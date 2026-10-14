package de.raindancer.modules.sends.service;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.network.NetworkCooldowns;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.sends.SendsSettings;
import de.raindancer.modules.sends.rules.SendableItemsRule;
import de.raindancer.modules.sends.store.MemorySendStorage;
import de.raindancer.modules.sends.store.SendBook;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static de.raindancer.modules.sends.service.SendServiceTest.item;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The wait between sends follows the player: sending from another server is not a way past it. */
class SendCooldownSharedTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final Messages messages = mock(Messages.class);
    private final SendServiceTest.Person alice = new SendServiceTest.Person("Alice");
    private final SendServiceTest.Person bob = new SendServiceTest.Person("Bob");

    @BeforeEach
    void network() {
        NetworkCooldowns.install(new NetworkCooldowns(null, now::get, task -> { }));
    }

    @AfterEach
    void standAlone() {
        NetworkCooldowns.install(null);
    }

    private SendService server() {
        Server server = mock(Server.class);
        when(server.getPlayer(alice.id)).thenReturn(alice.player);
        when(server.getPlayer(bob.id)).thenReturn(bob.player);
        SendBook book = new SendBook(new MemorySendStorage(), Runnable::run, now::get);
        return SendService.live(mock(Plugin.class), server, Log.of("test"), messages, mock(Effects.class), book,
                new SendableItemsRule(stack -> false), SendsSettings.DEFAULTS.withCooldownSeconds(30));
    }

    @Test
    @DisplayName("a send on one server starts the wait on every other")
    void followsThePlayer() {
        SendService here = server();
        SendService there = server();

        assertThat(here.create(alice.player, bob.id, List.of(item("diamond", 1)))).isTrue();

        assertThat(there.create(alice.player, bob.id, List.of(item("diamond", 1)))).isFalse();
        verify(messages).send(eq(alice.player), eq("sends.refuse.cooldown"), any(Object[].class));
    }
}
