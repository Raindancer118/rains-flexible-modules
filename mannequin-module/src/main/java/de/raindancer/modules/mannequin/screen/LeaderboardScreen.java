package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.PlayerTally;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * Every player who has hit this mannequin, ranked by their combined total damage across every
 * weapon — reachable from {@code StatsScreen}, sharing that page's tally and its one reset button.
 *
 * <p>A row is a player, not a (player, weapon) pair: "who has dealt the most damage overall"
 * is the question this page answers directly, and "with which weapon, and what did it do" is one
 * click deeper on {@link WeaponBreakdownScreen} — a player who has only ever used one weapon still
 * reaches the same answer, just with an extra click, rather than the top-level list turning into
 * ten near-duplicate rows for someone who switches weapons often.
 */
public final class LeaderboardScreen extends PaginatedMenu<PlayerTally> implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MannequinServices services;
    private final Mannequin mannequin;

    public LeaderboardScreen(MannequinServices services, Player viewer, Mannequin mannequin, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.mannequin = mannequin;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Leaderboard — " + mannequin.displayName());
    }

    @Override
    public String breadcrumb() {
        return "Leaderboard";
    }

    @Override
    protected List<PlayerTally> entries() {
        return services.registry().leaderboardFor(mannequin.id()).rankedByTotalDamage();
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>Nobody has hit this mannequin yet");
    }

    @Override
    protected ItemStack icon(PlayerTally tally) {
        // entries() is already sorted by rank, so its own position in that list is the rank —
        // cheap enough at the size a training leaderboard actually reaches.
        int rank = entries().indexOf(tally) + 1;
        String name = Bukkit.getOfflinePlayer(tally.player()).getName();
        String label = name == null ? tally.player().toString() : name;
        return Icons.head(tally.player(), "<white>#" + rank + " " + label,
                "<gray>Total damage: <white>"
                        + String.format(Locale.ROOT, "%.1f", tally.totalDamage()),
                "<gray>Hits: <white>" + tally.totalHits(),
                "<gray>Weapons used: <white>" + tally.weaponCount(),
                "",
                "<gray>Click to see the weapon breakdown.");
    }

    @Override
    protected void onClick(PlayerTally tally, InventoryClickEvent event) {
        new WeaponBreakdownScreen(services, viewer, mannequin, tally.player(), this).open();
    }

    @Override
    public String describe() {
        return "every player who has hit a mannequin, ranked by total damage dealt";
    }
}
