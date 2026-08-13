package de.raindancer.modules.hungergames.listener;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@link AdminHotbarListener#isFree} — the fix for the toolkit overwriting real gear an operator happened
 * to be holding at slots 7 and 8 while simply playing, not managing a tournament. The toolkit follows an
 * op across every world by design (see the class note on why this is not a location check like the other
 * two listeners in this incident's audit), so the fix is this predicate: a slot is only ever claimed when
 * nothing real is already standing in it.
 */
class AdminHotbarListenerTest {

    private ItemStack realItem(Material material) {
        ItemStack item = mock(ItemStack.class);
        lenient().when(item.getType()).thenReturn(material);
        return item;
    }

    @Test
    void anEmptySlotIsFree() {
        assertThat(AdminHotbarListener.isFree(null)).isTrue();
    }

    @Test
    void anAirSlotIsFree() {
        assertThat(AdminHotbarListener.isFree(realItem(Material.AIR))).isTrue();
    }

    @Test
    void aSlotHoldingRealGearIsNotFree() {
        assertThat(AdminHotbarListener.isFree(realItem(Material.DIAMOND_PICKAXE))).isFalse();
    }
}
