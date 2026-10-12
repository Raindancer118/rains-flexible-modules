package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.model.Kit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Picking a kit — and, where the survival server can be asked, "your own gear": a copy of what you carry there. A copy:
 * nothing used, broken or lost in the arena is missing from survival afterwards, and nothing gained here goes back.
 */
public final class KitPickMenu extends PaginatedMenu<String> implements IArenaScreen {

    private final ArenaServices services;
    private final boolean offerOwnGear;
    private final Consumer<String> chosen;

    public KitPickMenu(ArenaServices services, Player viewer, Menu parent, boolean offerOwnGear, Consumer<String> chosen) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.offerOwnGear = offerOwnGear;
        this.chosen = chosen;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Choose a kit");
    }

    @Override
    protected List<String> entries() {
        List<String> ids = new ArrayList<>();
        if (offerOwnGear) {
            ids.add(MatchSpec.OWN_GEAR);
        }
        services.kits().all().stream().filter(kit -> !kit.harmless()).map(Kit::id).forEach(ids::add);
        return ids;
    }

    @Override
    protected org.bukkit.inventory.ItemStack icon(String id) {
        if (id.equals(MatchSpec.OWN_GEAR)) {
            return Icons.of(Material.ENDER_CHEST, "<light_purple>Your own gear",
                    "<gray>A copy of what you carry on survival.",
                    "<gray>Nothing you use or lose here is missing there.");
        }
        Kit kit = services.kits().get(id).orElseThrow();
        Material icon = Material.matchMaterial(kit.icon());
        return Icons.of(icon == null ? Material.IRON_SWORD : icon, "<gold>" + kit.name(),
                "<gray>Click to choose.", "<gray>Right click to look inside.");
    }

    @Override
    protected void onClick(String id, InventoryClickEvent event) {
        if (event.isRightClick() && !id.equals(MatchSpec.OWN_GEAR)) {
            services.kits().get(id).ifPresent(kit -> new KitPreviewMenu(services, viewer, this, kit).open());
            return;
        }
        chosen.accept(id);
    }

    @Override
    public String breadcrumb() {
        return "kits";
    }

    @Override
    public String describe() {
        return "choosing a kit";
    }
}
