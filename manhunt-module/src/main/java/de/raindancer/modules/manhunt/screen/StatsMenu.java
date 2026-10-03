package de.raindancer.modules.manhunt.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.stats.HuntSummary;
import de.raindancer.modules.manhunt.stats.PlayerStats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.UUID;

/** One player's numbers across every hunt this server kept. */
public final class StatsMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final Pages pages;
    private final UUID whose;

    public StatsMenu(Pages pages, Player viewer, Menu parent, UUID whose) {
        super(viewer, pages.services().brand(), parent);
        this.pages = pages;
        this.whose = whose;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Stats");
    }

    @Override
    public String breadcrumb() {
        return "Stats";
    }

    @Override
    protected void render() {
        boolean known = pages.services().chronicle().stats().has(whose);
        PlayerStats stats = pages.services().chronicle().stats().get(whose);
        String name = known ? stats.name() : pages.services().desk().nameOf(whose);
        int rank = pages.services().chronicle().stats().ranked(de.raindancer.modules.manhunt.stats.StatsStore.Board.RATING)
                .indexOf(whose) + 1;
        cell(0, 4, Icons.head(whose, "<gold>" + MINI.escapeTags(name),
                known ? "<gray>Rating <white>" + Math.round(stats.rating()) + (rank > 0 ? " <gray>(#" + rank + ")" : "")
                        : "<gray>No hunts on record yet."), null);
        band(1, 2, Icons.of(Material.WRITABLE_BOOK, "<white>" + stats.hunts() + " hunt(s)",
                "<gray>" + stats.runnerHunts() + " running, " + stats.hunterHunts() + " hunting."), null);
        band(1, 4, Icons.of(Material.FEATHER, "<green>" + stats.runnerWins() + " win(s) as a Runner",
                "<gray>Out of " + stats.runnerHunts() + "."), null);
        band(1, 6, Icons.of(Material.IRON_SWORD, "<red>" + stats.hunterWins() + " win(s) as a Hunter",
                "<gray>Out of " + stats.hunterHunts() + "."), null);
        band(2, 2, Icons.of(Material.LEAD, "<gold>" + stats.catches() + " catch(es)", "<gray>Runners they caught."), null);
        band(2, 4, Icons.of(Material.SKELETON_SKULL, "<gray>" + stats.deaths() + " death(s)",
                "<gray>Caught " + stats.timesCaught() + " time(s) as a Runner."), null);
        band(2, 6, Icons.of(Material.CLOCK, "<aqua>Longest run: " + HuntSummary.clock(stats.bestSurvivalMillis()),
                "<gray>Altogether " + HuntSummary.clock(stats.survivedMillis()) + " on the run."), null);
        band(3, 3, Icons.of(Material.LEATHER_BOOTS, "<white>" + Math.round(stats.distance()) + " blocks",
                "<gray>Travelled across every hunt."), null);
        band(3, 5, Icons.of(Material.OBSIDIAN, "<light_purple>" + stats.portals() + " portal(s)",
                "<gray>Taken across every hunt."), null);
        toolbar(4, Icons.of(Material.GOLDEN_HELMET, "<gold>Leaderboard"),
                click -> pages.open(viewer, ManhuntServices.Page.LEADERBOARD, this));
    }
}
