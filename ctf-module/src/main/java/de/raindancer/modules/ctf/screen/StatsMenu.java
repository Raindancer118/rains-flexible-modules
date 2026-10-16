package de.raindancer.modules.ctf.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.ctf.CtfServices;
import de.raindancer.modules.ctf.model.Stats;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

/** Somebody's record: rounds, wins, captures, returns, kills, the fastest capture, their level. */
public final class StatsMenu extends Menu implements ICtfScreen {

    private final CtfServices services;
    private final Stats stats;

    private StatsMenu(CtfServices services, Player viewer, Menu parent, Stats stats) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.stats = stats;
    }

    /** Reads the record off the server thread, then opens on the viewer's. */
    public static void open(CtfServices services, Player viewer, Menu parent, UUID whose) {
        Scheduling.async(services.plugin(), () -> {
            String name = whose.equals(viewer.getUniqueId()) ? viewer.getName()
                    : java.util.Optional.ofNullable(Bukkit.getOfflinePlayer(whose).getName()).orElse("?");
            Stats stats = services.stats().of(whose).orElse(Stats.none(whose, name));
            Scheduling.entity(services.plugin(), viewer, () -> new StatsMenu(services, viewer, parent, stats).open());
        });
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Record of " + stats.name());
    }

    @Override
    protected void render() {
        int level = services.progress().levelRule().level(stats.xp());
        float into = services.progress().levelRule().progress(stats.xp());
        band(1, 4, Icons.head(stats.player(), "<gold>" + stats.name(), "<gray>Level <white>" + level
                + " <gray>(" + Math.round(into * 100) + "% to " + (level + 1) + ")", "<gray>Experience <white>"
                + stats.xp()));
        band(2, 1, Icons.of(Material.CLOCK, "<white>Rounds", "<white>" + stats.rounds()));
        band(2, 2, Icons.of(Material.GOLDEN_HELMET, "<gold>Wins", "<white>" + stats.wins() + " <gray>won, <white>"
                + stats.losses() + " <gray>lost"));
        band(2, 3, Icons.of(Material.RED_BANNER, "<red>Captures", "<white>" + stats.captures()));
        band(2, 4, Icons.of(Material.SHIELD, "<aqua>Returns", "<white>" + stats.returns()));
        band(2, 5, Icons.of(Material.IRON_SWORD, "<white>Kills", "<white>" + stats.kills() + " <gray>kills, <white>"
                + stats.deaths() + " <gray>deaths", "<white>" + stats.carrierKills() + " <gray>carriers stopped"));
        band(2, 6, Icons.of(Material.NETHER_STAR, "<yellow>MVP", "<white>" + stats.mvps() + " <gray>times"));
        band(2, 7, Icons.of(Material.FEATHER, "<green>Fastest capture", stats.fastestCapture() <= 0 ? "<gray>none yet"
                : "<white>" + String.format(Locale.ROOT, "%.1f", stats.fastestCapture() / 1000.0) + " s"));
    }

    @Override
    public String breadcrumb() {
        return "record";
    }

    @Override
    public String describe() {
        return "a player's record";
    }
}
