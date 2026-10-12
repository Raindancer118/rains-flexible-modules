package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.arena.ArenaServices;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** The arena's front page: fight players, fight waves, the kits, the best, watching, and the way back. */
public final class ArenaMenu extends Menu implements IArenaScreen {

    private final ArenaServices services;

    public ArenaMenu(ArenaServices services, Player viewer) {
        super(viewer, services.brand(), null);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>The Arena");
    }

    @Override
    protected void render() {
        boolean queued = services.queue().entryOf(viewer.getUniqueId()).isPresent();
        boolean fighting = services.matches().inFight(viewer.getUniqueId());
        band(1, 1, !fighting, Icons.of(Material.IRON_SWORD, "<gold>Fight players",
                "<gray>Duel, sumo, teams or everybody against everybody.",
                "<gray>Find an opponent or ask somebody."), "You are in a fight.",
                click -> new ModeMenu(services, viewer, this).open());
        band(1, 3, !fighting, Icons.of(Material.ZOMBIE_HEAD, "<red>Fight waves",
                "<gray>Mobs, wave after wave — alone or with a friend.",
                "<gray>Six quick presets, or set it up yourself."), "You are in a fight.",
                click -> new WavesMenu(services, viewer, this).open());
        band(1, 4, Icons.of(Material.ARMOR_STAND, "<aqua>Kits", "<gray>See what every kit holds."),
                click -> new KitPickMenu(services, viewer, this, false, kit -> {
                    services.kits().get(kit).ifPresent(found -> new KitPreviewMenu(services, viewer, this, found).open());
                }).open());
        band(1, 5, Icons.of(Material.GOLDEN_HELMET, "<yellow>The best", "<gray>Most wins, best waves, your own."),
                click -> StatsMenu.open(services, viewer, this));
        band(1, 7, !services.matches().fights().isEmpty(), Icons.of(Material.ENDER_EYE, "<light_purple>Watch a fight",
                "<gray>" + services.matches().fights().size() + " running."), "Nobody is fighting right now.",
                click -> new SpectateMenu(services, viewer, this).open());
        if (queued) {
            toolbar(2, Icons.of(Material.BARRIER, "<red>Leave the queue", "<gray>Stop waiting for a fight."), click -> {
                services.queue().leave(viewer.getUniqueId());
                services.messages().send(viewer, "arena.queue.left");
                refresh();
            });
        }
        toolbar(6, Icons.of(Material.OAK_DOOR, "<green>Back to survival", "<gray>Everything you own is there."), click -> {
            viewer.closeInventory();
            services.matches().left(viewer.getUniqueId());
            services.arena().leave(viewer);
        });
    }

    @Override
    public String breadcrumb() {
        return "the arena";
    }

    @Override
    public String describe() {
        return "the arena's front page";
    }
}
