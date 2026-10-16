package de.raindancer.modules.ctf.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Stats;
import de.raindancer.modules.ctf.store.StatsStore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.LocalDate;
import java.util.List;

/** The best: most captures of all time, most wins of all time, and this week's best capturers. */
public final class TopMenu extends Menu implements ICtfScreen {

    private final CtfServices services;
    private final List<Stats> captures;
    private final List<Stats> wins;
    private final List<StatsStore.Weekly> week;

    private TopMenu(CtfServices services, Player viewer, Menu parent, List<Stats> captures, List<Stats> wins,
                    List<StatsStore.Weekly> week) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.captures = captures;
        this.wins = wins;
        this.week = week;
    }

    public static void open(CtfServices services, Player viewer, Menu parent) {
        Scheduling.async(services.plugin(), () -> {
            List<Stats> captures = services.stats().top(StatsStore.Board.CAPTURES, 7);
            List<Stats> wins = services.stats().top(StatsStore.Board.WINS, 7);
            List<StatsStore.Weekly> week = services.stats().week(LocalDate.now().toEpochDay() / 7, 7);
            Scheduling.entity(services.plugin(), viewer,
                    () -> new TopMenu(services, viewer, parent, captures, wins, week).open());
        });
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>The best");
    }

    @Override
    protected void render() {
        for (int index = 0; index < captures.size(); index++) {
            Stats each = captures.get(index);
            band(1, index + 1, Icons.head(each.player(), "<red>#" + (index + 1) + " " + each.name(),
                    "<gray>" + each.captures() + " captures, all time"));
        }
        for (int index = 0; index < wins.size(); index++) {
            Stats each = wins.get(index);
            band(2, index + 1, Icons.head(each.player(), "<gold>#" + (index + 1) + " " + each.name(),
                    "<gray>" + each.wins() + " wins, all time"));
        }
        for (int index = 0; index < week.size(); index++) {
            StatsStore.Weekly each = week.get(index);
            band(3, index + 1, Icons.head(each.player(), "<aqua>#" + (index + 1) + " " + each.name(),
                    "<gray>" + each.captures() + " captures, " + each.wins() + " wins this week"));
        }
        if (captures.isEmpty() && week.isEmpty()) {
            band(2, 4, Icons.of(Material.PAPER, "<gray>Nobody yet", "<gray>Be the first."));
        }
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
