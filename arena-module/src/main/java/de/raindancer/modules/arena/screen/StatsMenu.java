package de.raindancer.modules.arena.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.arena.ArenaServices;
import de.raindancer.modules.arena.model.Stats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Your own record, the ten with most wins, and the ten who got furthest in mob waves. */
public final class StatsMenu extends Menu implements IArenaScreen {

    private final ArenaServices services;

    public StatsMenu(ArenaServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>The best");
    }

    @Override
    protected void render() {
        Stats mine = services.stats().of(viewer.getUniqueId()).orElse(Stats.none(viewer.getUniqueId(), viewer.getName()));
        set(4, Icons.head(viewer, "<gold>You", "<gray>Wins: <white>" + mine.wins() + "  <gray>Losses: <white>" + mine.losses()
                + "  <gray>Draws: <white>" + mine.draws(), "<gray>Kills: <white>" + mine.kills() + "  <gray>Defeats: <white>"
                + mine.deaths(), "<gray>Best wave: <white>" + mine.bestWave()));
        List<Stats> wins = services.stats().top(false, 9);
        for (int index = 0; index < wins.size(); index++) {
            Stats each = wins.get(index);
            set(9 + index, Icons.head(each.player(), "<yellow>#" + (index + 1) + " " + each.name(),
                    "<gray>" + each.wins() + " wins, " + each.kills() + " kills"));
        }
        List<Stats> waves = services.stats().top(true, 9);
        for (int index = 0; index < waves.size(); index++) {
            Stats each = waves.get(index);
            set(27 + index, Icons.head(each.player(), "<red>#" + (index + 1) + " " + each.name(),
                    "<gray>Wave " + each.bestWave()));
        }
        set(18, Icons.of(Material.GOLDEN_SWORD, "<yellow>Most wins ↑"));
        set(36, Icons.of(Material.ZOMBIE_HEAD, "<red>Furthest in mob waves ↑"));
    }

    @Override
    public String breadcrumb() {
        return "the best";
    }

    @Override
    public String describe() {
        return "the leaderboards";
    }
}
