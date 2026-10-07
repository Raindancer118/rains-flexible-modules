package de.miraculixx.veinminer;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * A stand-in with the exact name of Veinminer 2.x's own event (de.miraculixx.veinminer, a Kotlin
 * object with this nested class), so the tests check the name the module really looks for. Veinminer
 * fires one for every extra block of a vein, and breaks that block only if it was not cancelled.
 */
public final class VeinMinerEvent {

    private VeinMinerEvent() {
    }

    public static final class VeinminerEvent extends BlockBreakEvent {
        public VeinminerEvent(Block block, Player player) {
            super(block, player);
        }
    }
}
