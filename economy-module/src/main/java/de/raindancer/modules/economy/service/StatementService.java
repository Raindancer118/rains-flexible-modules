package de.raindancer.modules.economy.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.EconomySettings;
import de.raindancer.modules.economy.model.Transaction;
import de.raindancer.modules.economy.store.AccountBook;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The bank statement as a real book: a cover page with the balance, then every line, newest first, a few
 * to a page. Opened to read, or printed as a written book to keep — a Kontoauszug.
 */
public final class StatementService implements IEconomyService {

    /** A book holds a hundred pages; a statement holds this many lines, three to a page. */
    private static final int LINES = 240;
    private static final int PER_PAGE = 3;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    private final Plugin plugin;
    private final Server server;
    private final RainEconomy economy;
    private final AccountBook book;
    private volatile EconomySettings settings;

    public StatementService(Plugin plugin, Server server, RainEconomy economy, EconomySettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.economy = economy;
        this.book = economy.book();
        settings(settings);
    }

    @Override
    public void settings(EconomySettings updated) {
        this.settings = updated == null ? EconomySettings.DEFAULTS : updated;
    }

    /** Opens somebody's statement for reading. The lines are read off the server's threads first. */
    public void open(Player viewer, UUID whose, String name) {
        Scheduling.async(plugin, () -> {
            Book statement = statement(whose, name, book.history(whose, LINES, 0));
            Scheduling.entity(plugin, viewer, () -> viewer.openBook(statement));
        });
    }

    /** Prints the viewer's own statement as a written book to keep. */
    public void print(Player viewer) {
        Scheduling.async(plugin, () -> {
            Book statement = statement(viewer.getUniqueId(), viewer.getName(),
                    book.history(viewer.getUniqueId(), LINES, 0));
            Scheduling.entity(plugin, viewer, () -> {
                ItemStack written = new ItemStack(Material.WRITTEN_BOOK);
                BookMeta meta = (BookMeta) written.getItemMeta();
                meta.title(statement.title());
                meta.author(statement.author());
                meta.pages(statement.pages());
                written.setItemMeta(meta);
                viewer.getInventory().addItem(written).values()
                        .forEach(left -> viewer.getWorld().dropItemNaturally(viewer.getLocation(), left));
            });
        });
    }

    Book statement(UUID whose, String name, List<Transaction> lines) {
        Currency currency = settings.currency();
        long now = System.currentTimeMillis();
        List<Component> pages = new ArrayList<>();
        pages.add(Component.text()
                .append(Component.text("Bank Statement", NamedTextColor.DARK_BLUE, TextDecoration.BOLD))
                .append(Component.newline())
                .append(Component.text(DAY.format(Instant.ofEpochMilli(now)), NamedTextColor.DARK_GRAY))
                .append(Component.newline()).append(Component.newline())
                .append(Component.text("Account holder", NamedTextColor.GRAY)).append(Component.newline())
                .append(Component.text(name, NamedTextColor.BLACK)).append(Component.newline()).append(Component.newline())
                .append(Component.text("Balance", NamedTextColor.GRAY)).append(Component.newline())
                .append(currency.render(economy.balance(whose))).append(Component.newline()).append(Component.newline())
                .append(Component.text(lines.isEmpty() ? "No transactions yet." : lines.size() + " transaction(s), newest first.",
                        NamedTextColor.DARK_GRAY))
                .build());
        for (int from = 0; from < lines.size(); from += PER_PAGE) {
            var page = Component.text();
            for (Transaction line : lines.subList(from, Math.min(lines.size(), from + PER_PAGE))) {
                boolean in = !line.delta().isNegative();
                Money size = in ? line.delta() : line.delta().negate();
                page.append(Component.text(WHEN.format(Instant.ofEpochMilli(line.at())), NamedTextColor.DARK_GRAY))
                        .append(Component.newline())
                        .append(Component.text(line.kind().label(), NamedTextColor.BLACK))
                        .append(Component.newline());
                String detail = detail(line);
                if (!detail.isEmpty()) {
                    page.append(Component.text(detail, NamedTextColor.GRAY)).append(Component.newline());
                }
                page.append(Component.text(in ? "+ " : "- ", in ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_RED))
                        .append(Component.text(currency.format(size), in ? NamedTextColor.DARK_GREEN : NamedTextColor.DARK_RED))
                        .append(Component.newline())
                        .append(Component.text("= " + currency.format(line.balance()), NamedTextColor.DARK_GRAY))
                        .append(Component.newline()).append(Component.newline());
            }
            pages.add(page.build());
        }
        return Book.book(Component.text("Statement " + DAY.format(Instant.ofEpochMilli(now))),
                Component.text("Bank"), pages);
    }

    private String detail(Transaction line) {
        if (line.other() != null) {
            String other = server.getOfflinePlayer(line.other()).getName();
            String who = other == null ? "somebody" : other;
            return (line.delta().isNegative() ? "to " : "from ") + who;
        }
        String reason = line.reason();
        return reason.length() > 38 ? reason.substring(0, 37) + "…" : reason;
    }
}
