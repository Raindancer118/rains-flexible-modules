package de.raindancer.modules.economy.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Account;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** The richest players, by what is in the bank. Cash in pockets does not count. */
public final class BaltopMenu extends PaginatedMenu<Account> implements IEconomyScreen {

    private final EconomyServices services;
    private final List<Account> ranking;

    public BaltopMenu(EconomyServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.ranking = services.leaderboard().ranking();
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Richest players");
    }

    @Override
    public String breadcrumb() {
        return "Richest";
    }

    @Override
    protected List<Account> entries() {
        return ranking;
    }

    @Override
    protected ItemStack icon(Account account) {
        int place = ranking.indexOf(account) + 1;
        String colour = place == 1 ? "<gold>" : place == 2 ? "<gray>" : place == 3 ? "<#cd7f32>" : "<white>";
        boolean you = account.id().equals(viewer.getUniqueId());
        return Icons.head(account.id(), colour + "#" + place + " " + account.name() + (you ? " <green>(you)" : ""),
                Mini.of(services.currency().render(account.balance())));
    }

    @Override
    protected void onClick(Account account, InventoryClickEvent event) {
        // Looking, not doing.
    }

    @Override
    protected List<String> helpLines() {
        return List.of("Ranked by what is in the bank.", "Cash somebody carries is not counted.");
    }

    @Override
    public String describe() {
        return "the richest players";
    }
}
