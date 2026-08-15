package de.raindancer.modules.essentials.service;

import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.identity.Identities;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AfkServiceTest {

    private static final EssentialsSettings SETTINGS =
            EssentialsSettings.DEFAULTS; // afkEnabled true, timeout 300s, broadcast true

    private final AtomicLong clock = new AtomicLong(0);
    private final Identities identities = mock(Identities.class);
    private final Messages messages = mock(Messages.class);
    private final Chat chat = mock(Chat.class);
    private final AfkService service = new AfkService(identities, messages, chat, clock::get, SETTINGS);

    private Player player(UUID id, String name) {
        Player who = mock(Player.class);
        when(who.getUniqueId()).thenReturn(id);
        when(who.getName()).thenReturn(name);
        return who;
    }

    @Nested
    @DisplayName("the sweep")
    class Sweep {

        @Test
        @DisplayName("marks somebody AFK once they have been silent past the timeout")
        void marksAfterTimeout() {
            Player who = player(UUID.randomUUID(), "Tom");
            service.activity(who);

            clock.set(SETTINGS.afkTimeout() * 1000L - 1);
            service.sweep(List.of(who));
            assertThat(service.isAfk(who.getUniqueId())).isFalse();

            clock.set(SETTINGS.afkTimeout() * 1000L);
            service.sweep(List.of(who));
            assertThat(service.isAfk(who.getUniqueId())).isTrue();
        }

        @Test
        @DisplayName("never un-marks anybody — only activity or the toggle does that")
        void neverUnmarks() {
            Player who = player(UUID.randomUUID(), "Tom");
            service.toggle(who); // marks them AFK regardless of the clock

            clock.set(1_000_000L);
            service.sweep(List.of(who));

            assertThat(service.isAfk(who.getUniqueId())).isTrue();
        }
    }

    @Nested
    @DisplayName("activity")
    class Activity {

        @Test
        @DisplayName("brings somebody back from AFK the moment it happens")
        void bringsThemBack() {
            Player who = player(UUID.randomUUID(), "Tom");
            service.toggle(who);
            assertThat(service.isAfk(who.getUniqueId())).isTrue();

            service.activity(who);
            assertThat(service.isAfk(who.getUniqueId())).isFalse();
        }

        @Test
        @DisplayName("resets the clock, so a sweep right after does not mark them")
        void resetsTheClock() {
            Player who = player(UUID.randomUUID(), "Tom");
            clock.set(1_000_000L);
            service.activity(who);

            clock.set(1_000_000L + SETTINGS.afkTimeout() * 1000L - 1);
            service.sweep(List.of(who));

            assertThat(service.isAfk(who.getUniqueId())).isFalse();
        }
    }

    @Nested
    @DisplayName("toggle")
    class Toggle {

        @Test
        @DisplayName("marks somebody AFK who was not, and back who was")
        void flipsBothWays() {
            Player who = player(UUID.randomUUID(), "Tom");

            service.toggle(who);
            assertThat(service.isAfk(who.getUniqueId())).isTrue();

            service.toggle(who);
            assertThat(service.isAfk(who.getUniqueId())).isFalse();
        }
    }

    @Test
    @DisplayName("forgetting somebody drops both their activity and their AFK state")
    void forgetDropsEverything() {
        Player who = player(UUID.randomUUID(), "Tom");
        service.toggle(who);

        service.forget(who.getUniqueId());

        assertThat(service.isAfk(who.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("a switched-off sweep marks nobody")
    void disabledSweepDoesNothing() {
        EssentialsSettings off = new EssentialsSettings(3, false, 300, true, true, true, true, 16,
                java.util.List.of(), java.util.List.of());
        AfkService disabled = new AfkService(identities, messages, chat, clock::get, off);
        Player who = player(UUID.randomUUID(), "Tom");
        disabled.activity(who);

        clock.set(1_000_000L);
        disabled.sweep(List.of(who));

        assertThat(disabled.isAfk(who.getUniqueId())).isFalse();
    }
}
