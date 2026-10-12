package de.raindancer.modules.guilds.screen;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.platform.util.Times;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.GuildEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The guild's log, newest first, or only the bank's lines. Read from guilds.db when opened; what happened in the
 * last couple of seconds may not be written yet, which the empty page and the toolbar's refresh say.
 */
public final class LogMenu extends PaginatedMenu<GuildEvent> implements IGuildsScreen {

    private static final int SHOWN = 500;

    private final GuildsServices services;
    private final TeamId guild;
    private final boolean bankOnly;
    private volatile List<GuildEvent> read;
    private volatile boolean reading;

    public LogMenu(GuildsServices services, Player viewer, Menu parent, TeamId guild, boolean bankOnly) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.guild = guild;
        this.bankOnly = bankOnly;
    }

    @Override
    protected Component title() {
        return Component.text(bankOnly ? "Statement" : "Log", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return bankOnly ? "Statement" : "Log";
    }

    /** The log is read off the server thread; the page shows "reading" until it arrives, then redraws. */
    @Override
    protected List<GuildEvent> entries() {
        List<GuildEvent> now = read;
        if (now == null && !reading) {
            reading = true;
            Set<GuildEvent.Kind> kinds = bankOnly ? EnumSet.of(GuildEvent.Kind.DEPOSIT, GuildEvent.Kind.WITHDRAWAL) : Set.of();
            Scheduling.async(services.plugin(), () -> {
                List<GuildEvent> found = services.book().events(guild, kinds, SHOWN, 0);
                Scheduling.entity(services.plugin(), viewer, () -> {
                    read = found;
                    reading = false;
                    refresh();
                });
            });
        }
        return now == null ? List.of() : now;
    }

    @Override
    protected ItemStack icon(GuildEvent event) {
        List<String> lore = new ArrayList<>();
        lore.add("<dark_gray>" + Times.ago(event.at()));
        event.actor().ifPresent(actor -> lore.add("<gray>By <white>" + GuildMenu.escaped(services.notifier().name(actor))));
        event.target().ifPresent(target -> lore.add("<gray>About <white>" + GuildMenu.escaped(services.notifier().name(target))));
        if (event.kind().isBank() || event.amount() != 0) {
            lore.add("<gray>Amount: <white>" + (event.kind().isBank() ? services.bank().format(event.amount()) : String.valueOf(event.amount())));
        }
        if (!event.detail().isBlank()) {
            lore.add("<gray>" + GuildMenu.escaped(event.detail()));
        }
        return Icons.of(event.kind().icon(), "<white>" + readable(event.kind()), lore);
    }

    private static String readable(GuildEvent.Kind kind) {
        String words = kind.key().replace('-', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    @Override
    protected ItemStack emptyIcon() {
        if (read == null) {
            return Icons.of(Material.CLOCK, "<gray>Reading the log…");
        }
        return Icons.of(Material.COBWEB, "<gray>Nothing written yet", "<gray>What just happened is written within", "<gray>a few seconds; click to look again.");
    }

    @Override
    protected void emptyAction(InventoryClickEvent event) {
        if (!reading) {
            read = null;
            refresh();
        }
    }

    @Override
    protected void onClick(GuildEvent event, InventoryClickEvent click) {
        services.messages().send(viewer, "guilds.log.line", "what", readable(event.kind()), "when", Times.ago(event.at()),
                "detail", event.detail());
    }

    @Override
    public String describe() {
        return bankOnly ? "the bank's statement" : "the guild's log";
    }
}
