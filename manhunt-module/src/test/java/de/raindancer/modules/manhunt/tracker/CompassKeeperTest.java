package de.raindancer.modules.manhunt.tracker;

import org.bukkit.Material;
import org.bukkit.entity.Allay;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Our compasses stay in their holder's own inventory. Core only refuses the drop; everything else a
 * player can do with an item — a chest, an ender chest, a bundle, an item frame, an allay — would put
 * a working compass where the other side can take it, and leave its holder "missing" one that
 * {@code /manhunt give}, a respawn or a login would then hand out again.
 */
@DisplayName("a compass stays with its holder")
class CompassKeeperTest {

    private final ItemStack compass = stack(Material.COMPASS);
    private final ItemStack bread = stack(Material.BREAD);
    private final ItemStack bundle = stack(Material.BUNDLE);
    private final CompassKeeper keeper = new CompassKeeper(stack -> stack == compass);
    private Player player;
    private PlayerInventory inventory;

    private static ItemStack stack(Material material) {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(material);
        return stack;
    }

    @BeforeEach
    void setUp() {
        player = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
    }

    /** A chest, an ender chest, a shulker box, a hopper — anything of that many slots that is not ours. */
    private static Inventory container(int size) {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getSize()).thenReturn(size);
        return inventory;
    }

    /** The player's own 2×2 grid, which is what the server sees on top of their own inventory. */
    private static Inventory ownGrid() {
        CraftingInventory grid = mock(CraftingInventory.class);
        when(grid.getSize()).thenReturn(5);
        return grid;
    }

    private InventoryClickEvent click(Inventory top, ItemStack current, ItemStack cursor, ClickType type) {
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        when(event.getView()).thenReturn(view);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(current);
        when(event.getCursor()).thenReturn(cursor);
        when(event.getClick()).thenReturn(type);
        return event;
    }

    private static Inventory workbench() {
        CraftingInventory grid = mock(CraftingInventory.class);
        when(grid.getSize()).thenReturn(10);
        return grid;
    }

    @Nested
    @DisplayName("with a container open")
    class ContainerOpen {

        @Test
        @DisplayName("it cannot be clicked or shift-clicked across — chest, ender chest, shulker, hopper alike")
        void notIntoAContainer() {
            // chest / ender chest / shulker 27, hopper 5, dispenser 9, a workbench's own grid 10
            for (Inventory top : List.of(container(27), container(5), container(9), workbench())) {
                InventoryClickEvent placing = click(top, null, compass, ClickType.LEFT);
                InventoryClickEvent shifting = click(top, compass, null, ClickType.SHIFT_LEFT);

                keeper.onClick(placing);
                keeper.onClick(shifting);

                verify(placing).setCancelled(true);
                verify(shifting).setCancelled(true);
            }
        }

        @Test
        @DisplayName("nor swapped in with a number key or the off-hand key")
        void notByAHotkey() {
            when(inventory.getItem(2)).thenReturn(compass);
            when(inventory.getItemInOffHand()).thenReturn(compass);
            InventoryClickEvent numberKey = click(container(27), null, null, ClickType.NUMBER_KEY);
            when(numberKey.getHotbarButton()).thenReturn(2);
            InventoryClickEvent offHand = click(container(27), null, null, ClickType.SWAP_OFFHAND);

            keeper.onClick(numberKey);
            keeper.onClick(offHand);

            verify(numberKey).setCancelled(true);
            verify(offHand).setCancelled(true);
        }

        @Test
        @DisplayName("nor dragged into it")
        void notDragged() {
            InventoryDragEvent drag = mock(InventoryDragEvent.class);
            InventoryView view = mock(InventoryView.class);
            Inventory top = container(27);
            when(view.getTopInventory()).thenReturn(top);
            when(drag.getView()).thenReturn(view);
            when(drag.getOldCursor()).thenReturn(compass);
            when(drag.getRawSlots()).thenReturn(Set.of(30, 4));

            keeper.onDrag(drag);

            verify(drag).setCancelled(true);
        }

        @Test
        @DisplayName("everything else still moves freely")
        void otherItemsMove() {
            InventoryClickEvent event = click(container(27), bread, null, ClickType.SHIFT_LEFT);

            keeper.onClick(event);

            verify(event, never()).setCancelled(true);
        }
    }

    @Test
    @DisplayName("in their own inventory it can be moved around as they like")
    void ownInventoryIsFree() {
        InventoryClickEvent event = click(ownGrid(), compass, null, ClickType.LEFT);

        keeper.onClick(event);

        verify(event, never()).setCancelled(true);
    }

    @Test
    @DisplayName("but never into a bundle, which could then be thrown away or handed over")
    void notIntoABundle() {
        InventoryClickEvent bundleOnCompass = click(ownGrid(), compass, bundle, ClickType.RIGHT);
        InventoryClickEvent compassOnBundle = click(ownGrid(), bundle, compass, ClickType.RIGHT);

        keeper.onClick(bundleOnCompass);
        keeper.onClick(compassOnBundle);

        verify(bundleOnCompass).setCancelled(true);
        verify(compassOnBundle).setCancelled(true);
    }

    @Test
    @DisplayName("an item frame or an allay is never handed one; a villager is still traded with")
    void entities() {
        when(inventory.getItem(EquipmentSlot.HAND)).thenReturn(compass);
        PlayerInteractEntityEvent frame = new PlayerInteractEntityEvent(player, mock(GlowItemFrame.class));
        PlayerInteractEntityEvent allay = new PlayerInteractEntityEvent(player, mock(Allay.class));
        PlayerInteractEntityEvent villager = new PlayerInteractEntityEvent(player, mock(Villager.class));

        keeper.onEntity(frame);
        keeper.onEntity(allay);
        keeper.onEntity(villager);

        assertThat(frame.isCancelled()).isTrue();
        assertThat(allay.isCancelled()).isTrue();
        assertThat(villager.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("an armour stand is never handed one")
    void armourStand() {
        PlayerArmorStandManipulateEvent event = mock(PlayerArmorStandManipulateEvent.class);
        when(event.getPlayerItem()).thenReturn(compass);

        keeper.onArmourStand(event);

        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("it never drops from a body — whoever killed them does not get it")
    void notFromABody() {
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        List<ItemStack> drops = new ArrayList<>(List.of(compass, bread));
        when(death.getDrops()).thenReturn(drops);

        keeper.onDeath(death);

        assertThat(drops).containsExactly(bread);
    }
}
