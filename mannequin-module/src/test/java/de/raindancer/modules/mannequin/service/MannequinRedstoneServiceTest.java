package de.raindancer.modules.mannequin.service;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The vanilla container-fullness formula, and its inverse, against known reference points — a
 * barrel's 27 slots holding {@code 27 * 64 = 1728} items at most.
 */
class MannequinRedstoneServiceTest {

    private static final int BARREL_SLOTS = 27;

    @Test
    @DisplayName("nothing in the container reads as no signal at all")
    void emptyIsZero() {
        assertThat(MannequinRedstoneService.signalForItems(0, BARREL_SLOTS)).isZero();
        assertThat(MannequinRedstoneService.itemsForSignal(0, BARREL_SLOTS)).isZero();
    }

    @Test
    @DisplayName("a full barrel reads the maximum signal, 15")
    void fullIsFifteen() {
        int max = BARREL_SLOTS * 64;
        assertThat(MannequinRedstoneService.signalForItems(max, BARREL_SLOTS)).isEqualTo(15);
        assertThat(MannequinRedstoneService.itemsForSignal(15, BARREL_SLOTS)).isEqualTo(max);
    }

    @Test
    @DisplayName("half full reads signal 8, vanilla's own well-known reference point")
    void halfFullIsEight() {
        int half = (BARREL_SLOTS * 64) / 2;
        assertThat(MannequinRedstoneService.signalForItems(half, BARREL_SLOTS)).isEqualTo(8);
    }

    @Test
    @DisplayName("the smallest possible amount already reads signal 1")
    void theSmallestAmountIsSignalOne() {
        int items = MannequinRedstoneService.itemsForSignal(1, BARREL_SLOTS);
        assertThat(items).isPositive();
        assertThat(MannequinRedstoneService.signalForItems(items, BARREL_SLOTS)).isEqualTo(1);
    }

    @Test
    @DisplayName("every level from 0 to 15 round-trips through the inverse")
    void everyLevelRoundTrips() {
        for (int level = 0; level <= 15; level++) {
            int items = MannequinRedstoneService.itemsForSignal(level, BARREL_SLOTS);
            assertThat(MannequinRedstoneService.signalForItems(items, BARREL_SLOTS))
                    .as("level %d", level)
                    .isEqualTo(level);
        }
    }

    @Test
    @DisplayName("out-of-range levels are clamped rather than producing nonsense")
    void outOfRangeIsClamped() {
        assertThat(MannequinRedstoneService.itemsForSignal(-5, BARREL_SLOTS)).isZero();
        assertThat(MannequinRedstoneService.itemsForSignal(99, BARREL_SLOTS))
                .isEqualTo(BARREL_SLOTS * 64);
    }

    @Test
    @DisplayName("a container with no slots at all never signals")
    void noSlotsNeverSignals() {
        assertThat(MannequinRedstoneService.itemsForSignal(10, 0)).isZero();
        assertThat(MannequinRedstoneService.signalForItems(10, 0)).isZero();
    }

    /**
     * The actual fix for the signal never reaching a comparator — see {@code
     * MannequinRedstoneService#refreshAdjacentComparators}'s own javadoc for the PaperMC/Paper#505
     * root cause this exists to work around. Tested on its own, in isolation from {@code pulse()},
     * because the rest of {@code pulse()} constructs an {@code ItemStack} through {@link
     * org.bukkit.Material#asItemType()}, which needs a live server's registry and cannot run under
     * a plain unit test — the same limitation the module already documents for its other
     * live-entity code paths.
     */
    @Nested
    @DisplayName("refreshing the comparator that actually reads the barrel")
    class RefreshingComparators {

        private Block neighborOf(Block barrel, BlockFace side, Material type) {
            Block neighbor = mock(Block.class);
            when(neighbor.getType()).thenReturn(type);
            when(barrel.getRelative(side)).thenReturn(neighbor);
            return neighbor;
        }

        @Test
        @DisplayName("every adjacent comparator is force-updated, every non-comparator neighbour is left alone")
        void onlyComparatorsAreTouched() {
            Block barrel = mock(Block.class);

            Block comparator = neighborOf(barrel, BlockFace.NORTH, Material.COMPARATOR);
            BlockState comparatorState = mock(BlockState.class);
            when(comparator.getState()).thenReturn(comparatorState);

            Block air = neighborOf(barrel, BlockFace.SOUTH, Material.AIR);
            Block dirt = neighborOf(barrel, BlockFace.EAST, Material.DIRT);
            neighborOf(barrel, BlockFace.WEST, Material.AIR);

            MannequinRedstoneService.refreshAdjacentComparators(barrel);

            verify(comparatorState).update(true, true);
            verify(air, never()).getState();
            verify(dirt, never()).getState();
        }

        @Test
        @DisplayName("only the four horizontal sides are ever asked about — never above or below")
        void onlyHorizontalNeighboursCount() {
            Block barrel = mock(Block.class);
            neighborOf(barrel, BlockFace.NORTH, Material.AIR);
            neighborOf(barrel, BlockFace.SOUTH, Material.AIR);
            neighborOf(barrel, BlockFace.EAST, Material.AIR);
            neighborOf(barrel, BlockFace.WEST, Material.AIR);

            MannequinRedstoneService.refreshAdjacentComparators(barrel);

            verify(barrel, never()).getRelative(BlockFace.UP);
            verify(barrel, never()).getRelative(BlockFace.DOWN);
        }
    }
}
