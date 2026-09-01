package de.raindancer.modules.xpbottle.listener;

import de.raindancer.core.ui.effect.Cues;
import de.raindancer.modules.xpbottle.XpBottleServices;
import de.raindancer.modules.xpbottle.model.Bottle;
import de.raindancer.modules.xpbottle.store.BottleTags;
import de.raindancer.modules.xpbottle.util.PermissionNodes;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;

/**
 * What a right click on a bottle does.
 *
 * <h2>The clicks</h2>
 * <ul>
 *   <li><b>An empty glass bottle, in the air.</b> Draws the holder's own experience into it, and
 *       hands them a bottle o' enchanting holding exactly that many points.</li>
 *   <li><b>A sneak on anything with something in it.</b> Pours it back, both kinds alike — one
 *       gesture to learn rather than one per sort of bottle.</li>
 *   <li><b>A filled plain bottle, not sneaking.</b> Deliberately <em>not</em> cancelled: it is a
 *       real bottle o' enchanting and it gets thrown like one. What it pays out on impact is
 *       {@code ThrownBottleListener}'s business, from the tag that travels on the item.</li>
 *   <li><b>A siphon, not sneaking.</b> Also not cancelled, for a different reason: the click has to
 *       reach vanilla for the client to enter the drink animation, and that animation is what tells
 *       the server the button is still down. Cancelling here is the one change that would stop the
 *       siphon working while leaving every test passing.</li>
 * </ul>
 *
 * <h2>Why an empty glass bottle is only read in the air</h2>
 * Right clicking a glass bottle at water, at a cauldron or at a beehive is how a player fills it,
 * and those are all {@code RIGHT_CLICK_BLOCK}. Answering those would take somebody's experience
 * every time they went for a water bottle, and they would have no way to stop it.
 *
 * <h2>Why an untagged bottle o' enchanting is left entirely alone</h2>
 * Vanilla ones are thrown, and players throw them. Only a stack carrying this module's tag is one of
 * ours; everything else keeps the behaviour it came with.
 */
public final class BottleUseListener implements IXpBottleListener {

    private final XpBottleServices services;

    public BottleUseListener(XpBottleServices services) {
        this.services = services;
    }

    /**
     * {@link EventPriority#NORMAL}, not {@code ignoreCancelled}: a right click on air is never
     * cancelled by anything, and a click cancelled for the <em>block</em> inside somebody else's
     * claim is not a reason to refuse a player their own bottle. What is respected is
     * {@code useItemInHand} being denied, which is the specific "this player may not use what they
     * are holding" answer.
     */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent event) {
        if (event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = event.getItem();
        if (isOurs(player.getInventory().getItemInMainHand()) && hand == EquipmentSlot.OFF_HAND) {
            // One click fires this twice, once per hand. The main hand holds a bottle, so that is
            // the one being used; without this the off-hand event would bottle a second time.
            return;
        }

        Optional<Bottle> read = BottleTags.read(held, services.config());
        if (read.isEmpty()) {
            return;
        }
        Bottle bottle = read.get();

        if (bottle.mayVacuum()) {
            onSiphon(event, player, held, bottle, hand);
            return;
        }
        if (!bottle.isEmpty()) {
            onFilledBottle(event, player, held, bottle);
            return;
        }
        onEmptyGlass(event, player, held);
    }

    /** An empty glass bottle: the plain path, and only in the air. */
    private void onEmptyGlass(PlayerInteractEvent event, Player player, ItemStack held) {
        if (!services.config().plainBottlesWork()) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;   // filling from water, a cauldron or a beehive is vanilla's click, not ours
        }
        if (held == null || held.getType() != Material.GLASS_BOTTLE) {
            return;
        }
        if (!player.hasPermission(PermissionNodes.FILL)) {
            return;   // silent: an ordinary glass bottle should still behave like one
        }
        event.setCancelled(true);
        services.bottling().fillPlain(player, held);
    }

    /**
     * A filled plain bottle: a sneak pours it out, anything else throws it.
     *
     * <p>Not cancelled when it is thrown, and that is the point of the plain path — it hands back a
     * real bottle o' enchanting, which flies and breaks like one. What it is worth on impact is
     * {@code ThrownBottleListener}'s business; nothing needs remembering here, because the amount
     * travels on the item.
     */
    private void onFilledBottle(PlayerInteractEvent event, Player player, ItemStack held,
                                Bottle bottle) {
        if (player.isSneaking()) {
            pour(event, player, held, bottle);
        }
    }

    /** A siphon: a sneak pours it out, anything else starts the draw. */
    private void onSiphon(PlayerInteractEvent event, Player player, ItemStack held, Bottle bottle,
                          EquipmentSlot hand) {
        if (player.isSneaking()) {
            pour(event, player, held, bottle);
            return;
        }
        if (!player.hasPermission(PermissionNodes.SIPHON)) {
            event.setCancelled(true);
            services.messages().send(player, "xpbottle.siphon.not-allowed");
            services.effects().play(player.getUniqueId(), Cues.NO);
            return;
        }
        if (bottle.isFull()) {
            event.setCancelled(true);
            services.messages().send(player, "xpbottle.bottle-full");
            services.effects().play(player.getUniqueId(), Cues.NO);
            return;
        }
        // Left alone on purpose — see this class's own note. The draw is started here and the
        // animation vanilla is about to begin is what keeps it going.
        services.siphon().began(player, hand);
    }

    private void pour(PlayerInteractEvent event, Player player, ItemStack held, Bottle bottle) {
        event.setCancelled(true);
        if (!player.hasPermission(PermissionNodes.POUR)) {
            services.messages().send(player, "xpbottle.pour.not-allowed");
            services.effects().play(player.getUniqueId(), Cues.NO);
            return;
        }
        services.bottling().pour(player, held, bottle);
    }

    private boolean isOurs(ItemStack stack) {
        return BottleTags.isTagged(stack)
                || (stack != null && stack.getType() == Material.GLASS_BOTTLE);
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered between clicks here; what a draw has pulled in belongs to
        // SiphonService, and SiphonHoldListener is what tells it somebody has gone.
    }

    @Override
    public String describe() {
        return "right clicks on bottles: filling one, pouring one back, and starting a siphon";
    }
}
