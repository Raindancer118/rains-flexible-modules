package de.miraculixx.veinminer;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExpEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * A stand-in with the exact names of Veinminer 2.x's own events (de.miraculixx.veinminer, a Kotlin
 * object with these nested classes), so the tests check the names and getters the module really
 * looks for. Veinminer fires a {@link VeinminerEvent} for every extra block of a vein and breaks
 * that block only if it was not cancelled; then a {@link VeinminerDropEvent} with the items it is
 * about to drop. Shapes read from veinminer-paper 2.12.3.
 */
public final class VeinMinerEvent {

    private VeinMinerEvent() {
    }

    public static final class VeinminerEvent extends BlockBreakEvent {
        private final Location sourceLocation;

        public VeinminerEvent(Block block, Player player) {
            this(block, player, null, 0);
        }

        public VeinminerEvent(Block block, Player breaker, Location sourceLocation, int exp) {
            super(block, breaker);
            this.sourceLocation = sourceLocation;
            setExpToDrop(exp);
        }

        public Location getSourceLocation() {
            return sourceLocation;
        }
    }

    public static final class VeinminerDropEvent extends BlockExpEvent {
        private final BlockState blockState;
        private final Player player;
        private final List<ItemStack> items;
        private final int exp;

        public VeinminerDropEvent(Block block, BlockState blockState, Player player, List<ItemStack> items, int exp) {
            super(block, exp);
            this.blockState = blockState;
            this.player = player;
            this.items = items;
            this.exp = exp;
        }

        public BlockState getBlockState() {
            return blockState;
        }

        public Player getPlayer() {
            return player;
        }

        public List<ItemStack> getItems() {
            return items;
        }

        public int getExp() {
            return exp;
        }
    }
}
