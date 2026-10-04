package de.raindancer.modules.speedrun;

import de.raindancer.core.world.movement.Moves;
import org.bukkit.event.player.PlayerMoveEvent;

/** What a frozen player's step came to: held in their block, however that was done. */
public final class Steps {

    private Steps() {
    }

    /** Whether the step was refused — they end the move in the block they started it in. */
    public static boolean held(PlayerMoveEvent event) {
        return event.isCancelled() || Moves.sameBlock(event.getFrom(), event.getTo());
    }

    /**
     * Held without snapping the head back: not cancelled (a cancel turns them back to where they
     * looked before, every tick, which reads as lag), only moved back into their block.
     */
    public static boolean heldLookingAround(PlayerMoveEvent event, float yaw) {
        return !event.isCancelled() && Moves.sameBlock(event.getFrom(), event.getTo())
                && event.getTo().getYaw() == yaw;
    }
}
