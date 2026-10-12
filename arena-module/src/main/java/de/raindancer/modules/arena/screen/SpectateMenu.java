package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.model.Fight;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The fights running now; a click watches one, and /arena hub stops watching. */
public final class SpectateMenu extends PaginatedMenu<Fight> implements IArenaScreen {

    private final ArenaServices services;

    public SpectateMenu(ArenaServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Watch a fight");
    }

    @Override
    protected List<Fight> entries() {
        return services.matches().fights();
    }

    @Override
    protected ItemStack icon(Fight fight) {
        List<String> names = new ArrayList<>();
        fight.players().forEach(player -> {
            Player online = services.server().getPlayer(player);
            names.add(online == null ? "?" : online.getName());
        });
        return Icons.of(Material.ENDER_EYE, "<light_purple>" + fight.spec().mode().title() + " in " + fight.pit().id(),
                "<gray>" + String.join(", ", names), "<gray>Click to watch.");
    }

    @Override
    protected void onClick(Fight fight, InventoryClickEvent event) {
        viewer.closeInventory();
        fight.players().stream().findFirst().ifPresent(fighter -> {
            if (services.matches().spectate(viewer, fighter)) {
                services.messages().send(viewer, "arena.spectate.watching");
            }
        });
    }

    @Override
    public String breadcrumb() {
        return "watching";
    }

    @Override
    public String describe() {
        return "choosing a fight to watch";
    }
}
