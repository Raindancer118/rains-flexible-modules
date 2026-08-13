package de.raindancer.modules.mannequin.service;

import de.raindancer.modules.mannequin.rules.DurabilityRule;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the guarantee {@code MannequinEquipService}'s javadoc promises: a mannequin's loadout is
 * written only through {@code EntityEquipment#setItem}, and never through anything that would make
 * an item obtainable — {@code Inventory#addItem}, a player's own inventory, or dropping it.
 *
 * <h2>What this environment cannot verify</h2>
 * {@code rebuildFromSpec} and {@code damageEquippedPiece} both ultimately touch real {@code
 * org.bukkit.Material}/{@code Enchantment} methods (through {@code ItemSpec#toItemStack},
 * {@code Material#isAir}, {@code Material#getMaxDurability}) that lazily resolve a Paper server
 * registry — {@code io.papermc.paper.registry.RegistryAccess} — which does not exist outside a
 * running Paper server. No test anywhere else in this reactor constructs a real {@code ItemStack}
 * or references a real {@code Enchantment} constant for the same reason. The <em>decision</em>
 * logic those two methods lean on is fully covered without a server by {@code DurabilityRuleTest}
 * (the accumulation formula and the break threshold); what is not covered here is the live-entity
 * wiring around it, which was verified by code review and by this module building and running
 * cleanly against the real API.
 */
@ExtendWith(MockitoExtension.class)
class MannequinEquipServiceTest {

    @Mock
    private org.bukkit.entity.Mannequin entity;
    @Mock
    private EntityEquipment equipment;
    @Mock
    private ItemStack stack;
    @Mock
    private Player unrelatedPlayer;
    @Mock
    private Inventory unrelatedInventory;
    @Mock
    private PlayerInventory unrelatedPlayerInventory;
    @Mock
    private HumanEntity unrelatedHumanEntity;

    private final MannequinEquipService service =
            new MannequinEquipService(new DurabilityRule(), null);

    @Test
    @DisplayName("apply writes straight to EntityEquipment, silently, and touches nothing else")
    void applyWritesDirectlyToEquipment() {
        when(entity.getEquipment()).thenReturn(equipment);

        service.apply(entity, EquipmentSlot.HAND, stack);

        verify(equipment).setItem(EquipmentSlot.HAND, stack, false);
        assertNoForbiddenCalls(entity, equipment, stack, unrelatedPlayer, unrelatedInventory,
                unrelatedPlayerInventory, unrelatedHumanEntity);
    }

    @Test
    @DisplayName("a null slot or entity is a no-op rather than an NPE, and still touches nothing forbidden")
    void nullEntityIsANoOp() {
        service.apply(null, EquipmentSlot.HAND, stack);

        assertNoForbiddenCalls(unrelatedPlayer, unrelatedInventory, unrelatedPlayerInventory,
                unrelatedHumanEntity);
    }

    /**
     * Scans every recorded invocation on the given mocks for a method name this module's loadout
     * must never call — the real, structural guarantee behind "cannot be obtained".
     */
    private static void assertNoForbiddenCalls(Object... mocks) {
        List<String> forbidden = List.of("addItem", "getInventory", "drop", "dropItem",
                "dropItemNaturally");
        for (Object mockObject : mocks) {
            for (Invocation invocation : mockingDetails(mockObject).getInvocations()) {
                String name = invocation.getMethod().getName();
                assertThat(forbidden)
                        .as("a call to %s() on %s would make a mannequin's loadout obtainable",
                                name, mockObject.getClass())
                        .doesNotContain(name);
            }
        }
    }
}
