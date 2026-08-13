package de.raindancer.modules.mannequin.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.mannequin.MannequinServices;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.model.WeaponTally;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * One player's weapons against one mannequin, ranked by total damage — the page {@link
 * LeaderboardScreen} opens when a row is clicked.
 *
 * <h2>The icon is the real weapon, not a stand-in for it</h2>
 * {@link WeaponTally#sample()} is a clone of the exact item a player was last holding when they hit
 * with it — enchants, a custom name, all of it — so hovering a row here works exactly the way
 * hovering a weapon's name in a vanilla death message already does. The combat numbers are appended
 * underneath whatever lore the real item already carries, never replacing it.
 */
public final class WeaponBreakdownScreen extends PaginatedMenu<WeaponTally> implements IMannequinScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final MannequinServices services;
    private final Mannequin mannequin;
    private final UUID player;

    public WeaponBreakdownScreen(MannequinServices services, Player viewer, Mannequin mannequin,
                                 UUID player, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.mannequin = mannequin;
        this.player = player;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Weapons — " + playerLabel());
    }

    @Override
    public String breadcrumb() {
        return playerLabel();
    }

    private String playerLabel() {
        String name = Bukkit.getOfflinePlayer(player).getName();
        return name == null ? player.toString() : name;
    }

    @Override
    protected List<WeaponTally> entries() {
        return services.registry().leaderboardFor(mannequin.id()).byPlayer()
                .getOrDefault(player, de.raindancer.modules.mannequin.model.PlayerTally.empty(player))
                .rankedByTotalDamage();
    }

    /** Only reachable by clicking an existing leaderboard row, so this is a concurrent-reset edge case. */
    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No weapons recorded for " + playerLabel());
    }

    @Override
    protected ItemStack icon(WeaponTally tally) {
        List<String> stats = List.of(
                "",
                "<gray>Hits: <white>" + tally.hits(),
                "<gray>Total damage: <white>" + String.format(Locale.ROOT, "%.1f", tally.totalDamage()),
                "<gray>Average damage: <white>" + String.format(Locale.ROOT, "%.2f", tally.averageDamage()),
                "<gray>Highest hit: <white>" + String.format(Locale.ROOT, "%.1f", tally.highestHit()));

        ItemStack sample = tally.sample();
        if (sample == null || sample.getType() == Material.AIR) {
            List<String> lore = new ArrayList<>(List.of("<gray>Dealt with bare hands."));
            lore.addAll(stats);
            return Icons.of(Material.PLAYER_HEAD, "<white>Bare hands", lore);
        }

        ItemStack displayed = sample.clone();
        ItemMeta meta = displayed.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
        for (String line : stats) {
            lore.add(line.isEmpty() ? Component.empty() : MINI.deserialize(line));
        }
        meta.lore(lore);
        displayed.setItemMeta(meta);
        return displayed;
    }

    /** A leaf, not a door: the row is the whole answer. Redrawing on click still answers the
     * click rather than leaving it silent — a player who has just landed a hit while this was open
     * sees the updated numbers immediately instead of wondering whether the click did anything. */
    @Override
    protected void onClick(WeaponTally tally, InventoryClickEvent event) {
        refresh();
    }

    @Override
    public String describe() {
        return "one player's weapons against a mannequin, each with its own combat tally";
    }
}
