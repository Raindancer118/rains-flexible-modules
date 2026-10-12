package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Which kind of fight against players. */
public final class ModeMenu extends Menu implements IArenaScreen {

    private final ArenaServices services;

    public ModeMenu(ArenaServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Fight players");
    }

    @Override
    protected void render() {
        band(0, 0, Icons.of(Material.IRON_SWORD, "<gold>Duel", "<gray>One against one."),
                click -> pickKit(new MatchSpec(Mode.DUEL, null, 1, 0, 1, null)));
        band(0, 1, Icons.of(Material.STICK, "<yellow>Sumo", "<gray>No damage. Push them out of the ring."),
                click -> new FightOptionsMenu(services, viewer, this, new MatchSpec(Mode.SUMO, null, 1, 0, 1, null)).open());
        band(0, 2, Icons.of(Material.SHIELD, "<aqua>Teams of two", "<gray>Two against two, from the queue."),
                click -> pickKit(new MatchSpec(Mode.TEAMS, null, 1, 0, 2, null)));
        band(0, 3, Icons.of(Material.DIAMOND_SWORD, "<aqua>Teams of three", "<gray>Three against three, from the queue."),
                click -> pickKit(new MatchSpec(Mode.TEAMS, null, 1, 0, 3, null)));
        band(0, 4, Icons.of(Material.NETHERITE_SWORD, "<red>Free for all",
                "<gray>Three to eight, everybody against everybody.", "<gray>The last one standing wins."),
                click -> pickKit(new MatchSpec(Mode.FFA, null, 1, 0, 1, null)));
    }

    private void pickKit(MatchSpec spec) {
        new KitPickMenu(services, viewer, this, services.link().isUp(),
                kit -> new FightOptionsMenu(services, viewer, this, spec.withKit(kit)).open()).open();
    }

    @Override
    public String breadcrumb() {
        return "fight players";
    }

    @Override
    public String describe() {
        return "choosing a kind of fight";
    }
}
