package de.raindancer.modules.enchants.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.modules.enchants.service.AutopilotService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Where Autopilot can fly: every place in this world that is shared or the viewer's own (another player's private
 * home stays private), nearest first, then the players in this world — who are asked first.
 */
public final class AutopilotMenu extends PaginatedMenu<AutopilotMenu.Destination> {

    /** A place, or a player. */
    public record Destination(Poi place, UUID player) {
    }

    private final PoiStore places;
    private final AutopilotService autopilot;

    public AutopilotMenu(Player viewer, Brand brand, PoiStore places, AutopilotService autopilot) {
        super(viewer, brand, null);
        this.places = places;
        this.autopilot = autopilot;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<gray>Autopilot — where to?");
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Click a place: you watch yourself fly there.", "A player is asked first.");
    }

    @Override
    protected List<Destination> entries() {
        Location here = viewer.getLocation();
        String world = viewer.getWorld().getName();
        List<Destination> found = new ArrayList<>();
        places.inWorld(world).stream()
                .filter(place -> place.isHere() && (place.shared() || viewer.getUniqueId().equals(place.owner())))
                .sorted(Comparator.comparingDouble(place -> here.distanceSquared(new Location(viewer.getWorld(),
                        place.x(), place.y(), place.z()))))
                .forEach(place -> found.add(new Destination(place, null)));
        viewer.getWorld().getPlayers().stream()
                .filter(other -> !other.equals(viewer) && viewer.canSee(other) && other.getGameMode() != GameMode.SPECTATOR)
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(other -> found.add(new Destination(null, other.getUniqueId())));
        return found;
    }

    @Override
    protected ItemStack icon(Destination entry) {
        if (entry.place() != null) {
            Poi place = entry.place();
            long blocks = Math.round(Math.sqrt(viewer.getLocation().distanceSquared(new Location(viewer.getWorld(),
                    place.x(), place.y(), place.z()))));
            return Icons.of(place.icon() == null ? Material.ELYTRA : place.icon(), "<white>" + place.label(),
                    "<gray>" + place.kind() + " · " + blocks + " blocks", "<yellow>▶ Click to fly there");
        }
        Player other = viewer.getServer().getPlayer(entry.player());
        String name = other == null ? "?" : other.getName();
        // No distance: where somebody is stays theirs to share, which is what saying yes does.
        return Icons.head(entry.player(), "<white>" + name, "<gray>player", "<yellow>▶ Click to ask to fly to them");
    }

    @Override
    protected void onClick(Destination entry, InventoryClickEvent event) {
        viewer.closeInventory();
        if (entry.place() != null) {
            entry.place().location().ifPresent(to -> autopilot.take(viewer, to));
            return;
        }
        Player other = viewer.getServer().getPlayer(entry.player());
        if (other != null && autopilot.ready(viewer)) {
            autopilot.ask(viewer, other);
        }
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>Nowhere to fly", "<dark_gray>No shared places, homes or players in this world.");
    }
}
