package de.raindancer.modules.enchants.listener;

import de.raindancer.core.content.enchant.CustomEnchantments;
import de.raindancer.core.content.enchant.RainsEnchants;
import de.raindancer.modules.enchants.EnchantAdvancements;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts the blocks of one vein from Veinminer's own events and tells the advancements. Veinminer is
 * known by event name, never by dependency — the same way veintoggle knows it — so this runs, and
 * does nothing, without it.
 *
 * <p>One vein is one tick's worth of events, which is what makes a count per (player, tick) a count
 * per vein.
 */
public final class VeinAdvancementListener implements IEnchantsListener {

    private static final String VEIN_EVENT = "de.miraculixx.veinminer.VeinMinerEvent$VeinminerEvent";

    private record Run(int tick, int blocks) {
    }

    private final EnchantAdvancements advancements;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();

    public VeinAdvancementListener(EnchantAdvancements advancements) {
        this.advancements = advancements;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!VEIN_EVENT.equals(event.getClass().getName())) {
            return;
        }
        Player player = event.getPlayer();
        if (CustomEnchantments.levelOn(player.getInventory().getItemInMainHand(), RainsEnchants.VEINMINER) < 1) {
            return;
        }
        int tick = Bukkit.getCurrentTick();
        Run run = runs.merge(player.getUniqueId(), new Run(tick, 1),
                (old, fresh) -> old.tick() == tick ? new Run(tick, old.blocks() + 1) : fresh);
        advancements.vein(player.getUniqueId(), run.blocks());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer().getUniqueId());
    }

    @Override
    public void forget(UUID player) {
        runs.remove(player);
    }
}
