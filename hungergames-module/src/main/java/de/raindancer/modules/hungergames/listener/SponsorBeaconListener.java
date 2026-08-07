package de.raindancer.modules.hungergames.listener;

import de.raindancer.modules.hungergames.service.SponsorBeaconService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * A sponsor beacon opens the sponsor shop, not vanilla's own beacon interface.
 *
 * <h2>The bug this exists to fix</h2>
 * {@link SponsorBeaconService} places a real {@code Material.BEACON} block — see its own {@code BeaconBlock}
 * seam — and never had a listener to go with it. {@code SponsorBeaconService}'s class javadoc always said
 * so: {@code isSponsorBeacon} is "the query a listener elsewhere would use", and wiring the actual
 * {@code PlayerInteractEvent} handler was called out as outside that class's lane. Nothing ever picked it
 * up, so right-clicking a sponsor beacon opened Minecraft's own beacon GUI — a pyramid-and-power-selection
 * screen with no shop in it at all.
 *
 * <h2>Why {@code LOWEST}, and why that is enough here</h2>
 * Unlike a custom item's material, a beacon's own inventory is opened by the server itself as part of
 * handling the interaction, not by another plugin's listener — there is nothing racing this the way
 * WorldEdit's navigation wand raced the admin and spectator compasses. Cancelling the event at all, at any
 * priority before the server would otherwise open the beacon screen, is sufficient; {@code LOWEST} is
 * simply the convention every other "claim this click before anything else touches it" listener in this
 * module already uses.
 */
public final class SponsorBeaconListener implements IHungerGamesListener {

    private final SponsorBeaconService beacons;
    private final Consumer<Player> openShop;

    public SponsorBeaconListener(SponsorBeaconService beacons, Consumer<Player> openShop) {
        this.beacons = beacons;
        this.openShop = openShop;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.BEACON) {
            return;
        }
        Location at = new Location(clicked.getWorld(), clicked.getX(), clicked.getY(), clicked.getZ());
        if (!beacons.isSponsorBeacon(at)) {
            // An ordinary beacon somebody built themselves — the arena is not required to be empty of
            // them, and a tribute's own base defence must keep working exactly like vanilla.
            return;
        }
        event.setCancelled(true);
        openShop.accept(event.getPlayer());
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered here — every check reads SponsorBeaconService fresh, at the moment of
        // the click.
    }

    @Override
    public String describe() {
        return "a sponsor beacon opens the sponsor shop, not vanilla's own beacon interface";
    }
}
