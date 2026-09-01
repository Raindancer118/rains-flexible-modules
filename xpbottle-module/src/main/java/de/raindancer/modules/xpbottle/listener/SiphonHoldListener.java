package de.raindancer.modules.xpbottle.listener;

import de.raindancer.modules.xpbottle.XpBottleServices;
import de.raindancer.modules.xpbottle.store.BottleTags;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.UUID;

/**
 * The end of a draw, however it ends.
 *
 * <h2>Why every one of these is here</h2>
 * A siphon holds what it has pulled in memory until the draw stops, because writing it into the item
 * would end the draw on the spot — see {@code SiphonService}. That makes "the draw stopped" a thing
 * with several doors, and every door that is not watched is experience taken out of the world and
 * never given to anybody. Quitting, scrolling to another slot, swapping hands and dropping the
 * bottle are the four; releasing the button is the fifth and is noticed by the timer, which sees the
 * hand come down.
 *
 * <h2>Why the consume is cancelled</h2>
 * A siphon bottle is a potion so that holding right click starts a drink animation. Drinking it is
 * not wanted at all — the animation is. Its consume time is an hour, so this should never fire; it
 * is cancelled anyway, because "should never" is how a bottle ends up being swallowed on a server
 * with an item-duration plugin nobody here has heard of.
 */
public final class SiphonHoldListener implements IXpBottleListener {

    private final XpBottleServices services;

    public SiphonHoldListener(XpBottleServices services) {
        this.services = services;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (BottleTags.isTagged(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Before anything else forgets them: this is what writes a half-drawn bottle into the
        // inventory that is about to be saved.
        services.siphon().flush(event.getPlayer());
        forget(event.getPlayer().getUniqueId());
        services.bottling().forget(event.getPlayer().getUniqueId());
    }

    /**
     * {@link EventPriority#LOWEST}: Bukkit applies the new slot after the event has run, so the hand
     * still holds the bottle that was being drawn with. At a later priority another plugin could
     * have moved things about first, and the flush would find an unrelated item and hand the points
     * back to the player instead of putting them in the bottle they came from.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHeldSlotChanged(PlayerItemHeldEvent event) {
        services.siphon().flush(event.getPlayer());
    }

    /** {@link EventPriority#LOWEST}, for the reason above: the swap has not happened yet. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        services.siphon().flush(event.getPlayer());
    }

    /**
     * A dropped bottle is already out of the hand by the time this fires, so the flush cannot write
     * into it and gives the points to the player instead. That is the right answer rather than a
     * shortfall: they came out of orbs that no longer exist, and the person who drew them is the
     * only one with a claim on them.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        services.siphon().flush(event.getPlayer());
    }

    @Override
    public void forget(UUID player) {
        services.siphon().forget(player);
    }

    @Override
    public String describe() {
        return "the end of a siphon draw — quitting, scrolling, swapping hands or dropping it";
    }
}
