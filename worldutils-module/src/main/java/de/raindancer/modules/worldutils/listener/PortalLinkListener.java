package de.raindancer.modules.worldutils.listener;

import de.raindancer.core.world.manage.WorldFamily;
import de.raindancer.core.world.manage.WorldRegenerator;
import de.raindancer.modules.worldutils.WorldUtilsSettings;
import de.raindancer.modules.worldutils.rules.LandingRule;
import de.raindancer.modules.worldutils.store.ManagedWorldStore;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Makes the portals in worlds this module created lead to those worlds' own nether and end.
 *
 * <p>The correction is Core's {@link WorldFamily#redirect}; what is decided here is which worlds are
 * ours to correct — a family with at least one member made by {@code /worlds}, and never the server's
 * own level, whose portals already work. Items and mobs through a portal are corrected too, or a
 * farm's hopper line quietly delivers into the server's nether.
 *
 * <p>One case the redirect cannot do: the End's exit portal. The server aims that at the primary
 * overworld's spawn, and swapping only the world would keep those coordinates. Here it lands on the
 * player's own respawn point when that is in the family's overworld, else that overworld's spawn —
 * where vanilla's exit portal would have put them.
 */
public final class PortalLinkListener implements IWorldUtilsListener {

    private final ManagedWorldStore managed;
    private final Supplier<WorldUtilsSettings> settings;
    private final Function<String, World> lookup;

    public PortalLinkListener(ManagedWorldStore managed, Supplier<WorldUtilsSettings> settings,
                              Function<String, World> lookup) {
        this.managed = managed;
        this.settings = settings;
        this.lookup = lookup;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        corrected(event.getFrom(), event.getTo(), event.getPlayer()).ifPresent(event::setTo);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        corrected(event.getFrom(), event.getTo(), null).ifPresent(event::setTo);
    }

    /** Where this portal should lead instead, if it is one of ours and the server got it wrong. */
    Optional<Location> corrected(Location from, Location to, Player player) {
        if (!settings.get().linkPortals() || from == null || to == null
                || !from.isWorldLoaded() || !to.isWorldLoaded()) {
            return Optional.empty();
        }
        World fromWorld = from.getWorld();
        World toWorld = to.getWorld();
        if (fromWorld == null || toWorld == null || WorldRegenerator.isServerDimension(fromWorld)) {
            return Optional.empty();
        }
        WorldFamily family = WorldFamily.of(fromWorld.getName());
        if (family.members().stream().noneMatch(managed::isManaged)) {
            return Optional.empty();
        }
        if (fromWorld.getEnvironment() == World.Environment.THE_END
                && toWorld.getEnvironment() == World.Environment.NORMAL) {
            World home = lookup.apply(family.overworld());
            if (home == null || home.equals(toWorld)) {
                return Optional.empty();
            }
            return Optional.of(LandingRule.arrivalPoint(
                    player == null ? null : player.getRespawnLocation(), home));
        }
        return family.redirect(fromWorld, to, lookup);
    }

    @Override
    public void forget(UUID player) {
        // Nothing remembered per player.
    }

    @Override
    public String describe() {
        return "leading the portals of worlds made here to their own nether and end";
    }
}
