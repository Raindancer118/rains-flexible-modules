package de.raindancer.modules.xpbottle.service;

import de.raindancer.core.platform.network.NetworkCooldowns;
import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.xpbottle.XpBottleSettings;
import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.model.Bottling;
import de.raindancer.modules.xpbottle.rules.FillAmountRule;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The wait between bottlings follows the player: filling on another server is not a way past it. */
class BottlingCooldownSharedTest {

    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final ItemStack held = mock(ItemStack.class);
    private final BottleForge forge = mock(BottleForge.class);

    @BeforeEach
    void network() {
        NetworkCooldowns.install(new NetworkCooldowns(null, System::currentTimeMillis, task -> { }));
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<Integer, ItemStack>());
        when(player.calculateTotalExperiencePoints()).thenReturn(500);
        when(held.getAmount()).thenReturn(1);
    }

    @AfterEach
    void standAlone() {
        NetworkCooldowns.install(null);
    }

    private BottlingService server() {
        return new BottlingService(mock(Messages.class), mock(Effects.class), new FillAmountRule(), forge,
                XpBottleSettings.DEFAULTS.withFillCooldownSeconds(60));
    }

    @Test
    @DisplayName("a bottle filled on one server starts the wait on every other")
    void followsThePlayer() {
        BottlingService here = server();
        BottlingService there = server();

        assertThat(here.fillPlain(player, held).happened()).isTrue();

        Bottling second = there.fillPlain(player, held);
        assertThat(second.happened()).isFalse();
    }
}
