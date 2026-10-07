package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The paths that need no {@code Enchantment}: that type initialises Paper's registries, which a unit
 * test has no server for. The policy over levels and items is {@code EnchantRuleTest}'s.
 */
class EnchantServiceTest {

    private final Messages messages = mock(Messages.class);
    private final EnchantService service =
            new EnchantService(messages, mock(Audit.class), (who, task) -> task.run(), EssentialsSettings.DEFAULTS);
    private final PlayerInventory inventory = mock(PlayerInventory.class);

    private Player player(String name, String... nodes) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.getInventory()).thenReturn(inventory);
        for (String node : nodes) {
            when(player.hasPermission(node)).thenReturn(true);
        }
        return player;
    }

    @Test
    @DisplayName("stripping with empty hands says so")
    void nothingHeld() {
        Player me = player("Tom");
        ItemStack air = mock(ItemStack.class);
        when(air.isEmpty()).thenReturn(true);
        when(inventory.getItemInMainHand()).thenReturn(air);

        service.clear(me, me);

        verify(messages).send(eq(me), eq("essentials.enchant.nothing-held"), any(Object[].class));
    }

    @Test
    @DisplayName("stripping somebody else's needs the others node")
    void othersNeedTheirNode() {
        CommandSender staff = mock(CommandSender.class);
        Player them = player("Tom");

        service.clear(staff, them);

        verify(messages).send(eq(staff), eq("essentials.enchant.not-others"), any(Object[].class));
        verify(inventory, never()).setItemInMainHand(any());
    }

    @Test
    @DisplayName("stripping something with nothing on it is said, not pretended")
    void nothingToStrip() {
        Player me = player("Tom", PermissionNodes.ENCHANT);
        ItemStack plain = mock(ItemStack.class);
        when(plain.isEmpty()).thenReturn(false);
        when(plain.clone()).thenReturn(plain);
        when(plain.getEnchantments()).thenReturn(Map.of());
        when(inventory.getItemInMainHand()).thenReturn(plain);

        service.clear(me, me);

        verify(messages).send(eq(me), eq("essentials.enchant.nothing-to-clear"), any(Object[].class));
        verify(inventory, never()).setItemInMainHand(any());
    }
}
