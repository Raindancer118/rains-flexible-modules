package de.raindancer.modules.essentials.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepairServiceTest {

    private final Messages messages = mock(Messages.class);
    private final Audit audit = mock(Audit.class);
    private final RepairService service =
            new RepairService(messages, audit, (who, task) -> task.run(), EssentialsSettings.DEFAULTS);

    private final PlayerInventory inventory = mock(PlayerInventory.class);

    private Player player(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.getInventory()).thenReturn(inventory);
        return player;
    }

    private static Damageable worn(int damage) {
        Damageable meta = mock(Damageable.class);
        when(meta.getDamage()).thenReturn(damage);
        return meta;
    }

    private static ItemStack stack(Material material, ItemMeta meta) {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.isEmpty()).thenReturn(false);
        when(item.getItemMeta()).thenReturn(meta);
        when(item.clone()).thenReturn(item);
        return item;
    }

    @Test
    @DisplayName("the held tool is mended to zero damage")
    void repairsTheHand() {
        Player me = player("Tom");
        when(me.hasPermission(PermissionNodes.REPAIR)).thenReturn(true);
        Damageable meta = worn(120);
        ItemStack pick = stack(Material.DIAMOND_PICKAXE, meta);
        when(inventory.getItemInMainHand()).thenReturn(pick);

        service.repairHand(me, me);

        verify(meta).setDamage(0);
        verify(pick).setItemMeta(meta);
        verify(inventory).setItemInMainHand(pick);
        verify(audit).record(org.mockito.ArgumentMatchers.<AuditEntry.Builder>any());
    }

    @Test
    @DisplayName("an undamaged tool is left alone and the player is told so")
    void alreadyFine() {
        Player me = player("Tom");
        when(me.hasPermission(PermissionNodes.REPAIR)).thenReturn(true);
        Damageable meta = worn(0);
        ItemStack held = stack(Material.DIAMOND_PICKAXE, meta);
        when(inventory.getItemInMainHand()).thenReturn(held);

        service.repairHand(me, me);

        verify(meta, never()).setDamage(0);
        verify(messages).send(eq(me), eq("essentials.repair.already-fine"), any(Object[].class));
    }

    @Test
    @DisplayName("repairing everything counts only the damaged ones, in armour, offhand and storage alike")
    void repairsEverythingAndCounts() {
        Player me = player("Tom");
        when(me.hasPermission(PermissionNodes.REPAIR_ALL)).thenReturn(true);
        Damageable chestMeta = worn(30);
        Damageable swordMeta = worn(10);
        Damageable shieldMeta = worn(0);
        ItemStack chest = stack(Material.IRON_CHESTPLATE, chestMeta);
        ItemStack sword = stack(Material.IRON_SWORD, swordMeta);
        ItemStack shield = stack(Material.SHIELD, shieldMeta);
        ItemStack dirt = stack(Material.DIRT, mock(ItemMeta.class));
        when(inventory.getStorageContents()).thenReturn(new ItemStack[]{sword, dirt, null});
        when(inventory.getArmorContents()).thenReturn(new ItemStack[]{null, chest, null, null});
        when(inventory.getExtraContents()).thenReturn(new ItemStack[]{shield});

        service.repairAll(me, me);

        verify(chestMeta).setDamage(0);
        verify(swordMeta).setDamage(0);
        verify(shieldMeta, never()).setDamage(0);
        verify(messages).send(eq(me), eq("essentials.repair.all"), eq("things"), eq("2 things"));
    }

    @Test
    @DisplayName("a staff repair of somebody else's is audited and the owner is told")
    void staffRepair() {
        Player staff = player("Mod");
        Player them = player("Tom");
        when(staff.hasPermission(PermissionNodes.REPAIR_OTHERS)).thenReturn(true);
        when(staff.hasPermission(PermissionNodes.REPAIR)).thenReturn(true);
        Damageable meta = worn(5);
        ItemStack held = stack(Material.BOW, meta);
        when(inventory.getItemInMainHand()).thenReturn(held);

        service.repairHand(staff, them);

        verify(meta).setDamage(0);
        verify(audit).record(org.mockito.ArgumentMatchers.<AuditEntry.Builder>any());
        verify(messages).send(eq(them), eq("essentials.repair.by-staff"), any(Object[].class));
    }

    @Test
    @DisplayName("without the others node nothing is touched")
    void othersRefused() {
        CommandSender staff = mock(CommandSender.class);
        Player them = player("Tom");
        Damageable meta = worn(5);
        ItemStack held = stack(Material.BOW, meta);
        when(inventory.getItemInMainHand()).thenReturn(held);

        service.repairHand(staff, them);

        verify(meta, never()).setDamage(0);
        verify(messages).send(eq(staff), eq("essentials.repair.not-others"), any(Object[].class));
    }
}
