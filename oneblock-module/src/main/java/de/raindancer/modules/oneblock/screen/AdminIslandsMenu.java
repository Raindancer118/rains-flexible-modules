package de.raindancer.modules.oneblock.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.oneblock.OneBlockServices;
import de.raindancer.modules.oneblock.model.Hold;
import de.raindancer.modules.oneblock.model.Island;
import de.raindancer.modules.oneblock.service.IslandServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Every island, loaded ones first, each with what keeps it up or down. A click opens its admin page. */
public final class AdminIslandsMenu extends PaginatedMenu<Island> implements IOneBlockScreen {

    private final OneBlockServices services;
    private final IslandServer islands;

    public AdminIslandsMenu(OneBlockServices services, IslandServer islands, Player viewer, Menu parent) {
        super(viewer, islands.brand(), parent);
        this.services = services;
        this.islands = islands;
    }

    @Override
    protected Component title() {
        return Component.text("All islands", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Islands";
    }

    @Override
    protected List<Island> entries() {
        List<Island> all = new ArrayList<>(islands.book().all());
        all.sort(Comparator.comparing((Island island) -> !islands.worlds().anyLoaded(island))
                .thenComparing(Island::ownerName, String.CASE_INSENSITIVE_ORDER));
        return all;
    }

    @Override
    protected ItemStack icon(Island island) {
        boolean loaded = islands.worlds().anyLoaded(island);
        int players = islands.worlds().playersOn(island).size();
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + island.idPart());
        lore.add(loaded ? "<green>Loaded <gray>· " + players + " player(s) on it" : "<gray>Unloaded");
        lore.add("<gray>Money: " + services.messages().raw(island.mode().badgeKey()));
        lore.add("<gray>Team: <white>" + island.everyone().size() + " <gray>· Shards: <light_purple>" + island.shards());
        AdminIslandMenu.addHoldLine(lore, island, System.currentTimeMillis());
        lore.add(island.autoUnload() ? "<gray>Auto-unload: <green>on" : "<gray>Auto-unload: <red>off");
        lore.add("");
        lore.add("<yellow>Click to manage it.");
        Material icon = island.hold().filter(hold -> hold.activeAt(System.currentTimeMillis())).isPresent()
                ? (island.hold().get().kind() == Hold.Kind.UP ? Material.LIME_CONCRETE : Material.RED_CONCRETE)
                : loaded ? Material.GRASS_BLOCK : Material.DIRT;
        return Icons.of(icon, "<white>" + island.ownerName() + "'s island", lore);
    }

    @Override
    protected void onClick(Island island, InventoryClickEvent event) {
        new AdminIslandMenu(services, islands, viewer, this, island.id()).open();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No islands yet");
    }

    @Override
    public String describe() {
        return "every island, for staff";
    }
}
