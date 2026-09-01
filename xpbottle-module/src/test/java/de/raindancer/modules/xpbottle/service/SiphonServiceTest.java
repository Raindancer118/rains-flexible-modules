package de.raindancer.modules.xpbottle.service;

import de.raindancer.core.ui.actionbar.ActionBars;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.xpbottle.XpBottleSettings;
import de.raindancer.modules.xpbottle.rules.FillAmountRule;
import de.raindancer.modules.xpbottle.rules.SiphonReachRule;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When a siphon counts as being held down, and that nothing drawn is ever simply dropped.
 *
 * <p>The draw itself is not exercised here: it looks for nearby entities and reads a real
 * {@code ItemStack}'s persistent data, both of which lazily reach for a running Paper server. What
 * <em>is</em> exercised is the part that has no server in it and would be silently wrong — the two
 * signals that keep a draw alive, and where the points go when the bottle they were drawn with is
 * no longer in the hand.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SiphonServiceTest {

    @Mock
    private Plugin plugin;
    @Mock
    private Messages messages;
    @Mock
    private Effects effects;
    @Mock
    private ActionBars actionBars;
    @Mock
    private BottleForge forge;
    @Mock
    private BottlingService bottling;
    @Mock
    private Player player;

    private final UUID playerId = UUID.randomUUID();
    private SiphonService service;

    @BeforeEach
    void setUp() {
        service = new SiphonService(plugin, messages, effects, actionBars, new FillAmountRule(),
                new SiphonReachRule(), forge, bottling, XpBottleSettings.DEFAULTS);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isOnline()).thenReturn(true);
        when(player.hasActiveItem()).thenReturn(false);
    }

    @Test
    @DisplayName("nobody is drawing until they press the button")
    void nobodyDrawsByDefault() {
        assertThat(service.isDrawing(player)).isFalse();
    }

    @Test
    @DisplayName("the click alone keeps a draw alive across the first few ticks")
    void theClickCoversTheGap() {
        service.began(player, EquipmentSlot.HAND);

        assertThat(service.isDrawing(player))
                .as("the client has not entered the drink animation yet; without this the first "
                        + "runs of the timer would see nothing and the siphon would look dead")
                .isTrue();
    }

    @Test
    @DisplayName("a click nobody followed up on stops mattering, so a tap is not a draw for ever")
    void theClickExpires() {
        service.began(player, EquipmentSlot.HAND);
        for (int run = 0; run < 5; run++) {
            service.tick(List.of(), 4L);
        }

        assertThat(service.isDrawing(player)).isFalse();
    }

    @Test
    @DisplayName("an offline player is not drawing, whatever was remembered about them")
    void offlineIsNotDrawing() {
        service.began(player, EquipmentSlot.HAND);
        when(player.isOnline()).thenReturn(false);

        assertThat(service.isDrawing(player)).isFalse();
    }

    @Test
    @DisplayName("flushing with nothing drawn changes nothing")
    void flushingNothingDoesNothing() {
        assertThat(service.flush(player)).isZero();
        verify(player, never()).giveExp(org.mockito.ArgumentMatchers.anyInt());
        verify(forge, never()).dress(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("forgetting a player leaves nothing behind for them")
    void forgettingClearsEverything() {
        service.began(player, EquipmentSlot.HAND);

        service.forget(playerId);

        assertThat(service.isDrawing(player)).isFalse();
        assertThat(service.drawing()).isZero();
    }

    @Test
    @DisplayName("flushing everybody at once is safe on an empty server")
    void flushingNobodyIsSafe() {
        assertThat(service.flushAll(List.of())).isZero();
        assertThat(service.flushAll(null)).isZero();
    }
}
