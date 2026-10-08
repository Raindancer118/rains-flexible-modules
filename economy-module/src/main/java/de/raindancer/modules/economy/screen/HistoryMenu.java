package de.raindancer.modules.economy.screen;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.economy.EconomyServices;
import de.raindancer.modules.economy.model.Transaction;
import de.raindancer.modules.economy.util.Mini;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** One account's statement, newest first. Read from the database before this opens. */
public final class HistoryMenu extends PaginatedMenu<Transaction> implements IEconomyScreen {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
            .withZone(ZoneId.systemDefault());

    private final EconomyServices services;
    private final String whose;
    private final List<Transaction> lines;

    public HistoryMenu(EconomyServices services, Player viewer, Menu parent, String whose, List<Transaction> lines) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.whose = whose;
        this.lines = List.copyOf(lines);
    }

    @Override
    protected Component title() {
        return MiniMessage.miniMessage().deserialize("<dark_gray>Statement · " + whose);
    }

    @Override
    public String breadcrumb() {
        return "Statement";
    }

    @Override
    protected List<Transaction> entries() {
        return lines;
    }

    @Override
    protected ItemStack icon(Transaction line) {
        Currency currency = services.currency();
        boolean in = !line.delta().isNegative();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + line.kind().label());
        if (!line.reason().isEmpty()) {
            lore.add("<dark_gray>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                    .escapeTags(line.reason()));
        }
        if (line.other() != null) {
            String name = services.server().getOfflinePlayer(line.other()).getName();
            lore.add("<gray>" + (in ? "From " : "To ") + "<white>" + (name == null ? "somebody" : name));
        }
        lore.add("");
        lore.add("<gray>Balance after: " + Mini.of(currency.render(line.balance())));
        lore.add("<dark_gray>" + WHEN.format(Instant.ofEpochMilli(line.at())));
        return Icons.of(line.kind().icon(), (in ? "<green>+" : "<red>-")
                + Mini.of(currency.render(in ? line.delta() : line.delta().negate())), lore);
    }

    @Override
    protected void onClick(Transaction line, InventoryClickEvent event) {
        // A statement line is read, not acted on.
    }

    @Override
    public String describe() {
        return "one account's statement";
    }
}
