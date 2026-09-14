package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A Hunter's tracking compass does not drop when they die.
 *
 * <h2>Why it must not</h2>
 * Asked for directly, and the reason is sharper than tidiness: a dropped tracking compass is a live
 * tracking compass lying on the ground, and the player most likely to be standing over a dead
 * Hunter's body is the Runner who just killed them. A Runner holding one would see the needle swing
 * to their own side's position — the hunt handed to the wrong team by the Hunters losing a fight.
 *
 * <p>It is taken out of the drops rather than kept through death on purpose: keeping it would need
 * keep-inventory, which is the server's own rule and this module's to borrow, not to force. Removed
 * from the drops the compass is simply gone, and {@code tracker-give-on-respawn} (on by default)
 * hands the Hunter a fresh one a moment later.
 */
class TrackerListenerDropsTest {

    private TrackerCompassService tracker;
    private TrackerListener listener;
    private ManhuntService manhunt;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");
        manhunt = mock(ManhuntService.class);
        tracker = new TrackerCompassService(plugin, manhunt,
                new TrackerCompass(ManhuntSettings.DEFAULTS, new PortalMemory()), new PortalMemory(),
                null, null, ManhuntSettings.DEFAULTS);
        listener = new TrackerListener(manhunt, tracker, new PortalMemory());
    }

    /** An item the module's own marker says is one of ours. */
    private ItemStack trackingCompass() {
        ItemStack stack = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(stack.getType()).thenReturn(Material.COMPASS);
        when(stack.hasItemMeta()).thenReturn(true);
        when(stack.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(NamespacedKey.class), any(PersistentDataType.class))).thenReturn("tracker");
        return stack;
    }

    private ItemStack somethingElse() {
        ItemStack stack = mock(ItemStack.class);
        when(stack.getType()).thenReturn(Material.DIAMOND);
        when(stack.hasItemMeta()).thenReturn(false);
        return stack;
    }

    private PlayerDeathEvent deathDropping(List<ItemStack> drops) {
        PlayerDeathEvent event = mock(PlayerDeathEvent.class);
        when(event.getDrops()).thenReturn(drops);
        when(event.getEntity()).thenReturn(mock(Player.class));
        return event;
    }

    @Test
    @DisplayName("the tracking compass is taken out of a dead Hunter's drops")
    void theCompassDoesNotDrop() {
        ItemStack compass = trackingCompass();
        List<ItemStack> drops = new ArrayList<>(List.of(compass));

        listener.onDeath(deathDropping(drops));

        assertThat(drops).isEmpty();
    }

    @Test
    @DisplayName("everything else the Hunter was carrying still drops, untouched")
    void everythingElseStillDrops() {
        ItemStack compass = trackingCompass();
        ItemStack loot = somethingElse();
        List<ItemStack> drops = new ArrayList<>(List.of(loot, compass, somethingElse()));

        listener.onDeath(deathDropping(drops));

        assertThat(drops).hasSize(2).doesNotContain(compass).contains(loot);
    }

    @Test
    @DisplayName("a death with nothing of ours in it is left entirely alone")
    void unrelatedDeathIsUntouched() {
        List<ItemStack> drops = new ArrayList<>(List.of(somethingElse(), somethingElse()));

        listener.onDeath(deathDropping(drops));

        assertThat(drops).hasSize(2);
    }
}
