package de.raindancer.modules.veintoggle.listener;

import de.raindancer.modules.veintoggle.rules.VeinRule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Refuses Veinminer's extra blocks for a player who switched vein mining off, and tells them so once
 * per vein — a vein of thirty ores is thirty events, and thirty lines of chat is a wall.
 *
 * <p>LOWEST, so the refusal is in before anything that would act on the break; and it only ever cancels,
 * so it cannot undo another plugin's refusal.
 */
public final class VeinListener implements Listener {

    /** How long after one notice the next vein goes by quietly. */
    public static final long QUIET_FOR_MILLIS = 5_000L;

    private final VeinRule rule;
    private final Predicate<Player> wantsVeins;
    private final Consumer<Player> tellSkipped;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastTold = new ConcurrentHashMap<>();

    public VeinListener(VeinRule rule, Predicate<Player> wantsVeins, Consumer<Player> tellSkipped,
                        LongSupplier clock) {
        this.rule = rule;
        this.wantsVeins = wantsVeins;
        this.tellSkipped = tellSkipped;
        this.clock = clock;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!rule.isVeinBlock(event)) {
            return;
        }
        Player player = event.getPlayer();
        if (!rule.refuse(true, wantsVeins.test(player))) {
            return;
        }
        event.setCancelled(true);
        long now = clock.getAsLong();
        Long before = lastTold.get(player.getUniqueId());
        if (before == null || now - before > QUIET_FOR_MILLIS) {
            lastTold.put(player.getUniqueId(), now);
            tellSkipped.accept(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    /** Lets go of somebody who left, so the notice throttle does not keep everyone who ever played. */
    public void forget(UUID player) {
        lastTold.remove(player);
    }
}
