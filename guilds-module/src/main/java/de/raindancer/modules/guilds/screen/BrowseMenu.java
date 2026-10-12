package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.JoinMode;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Every guild, highest level first — also the leaderboard. Click to join or apply. */
public final class BrowseMenu extends PaginatedMenu<Guild> implements IGuildsScreen {

    private final GuildsServices services;

    public BrowseMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Every guild", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Every guild";
    }

    @Override
    protected List<Guild> entries() {
        return services.book().all().stream()
                .sorted(Comparator.comparingLong(Guild::xp).reversed().thenComparing(Guild::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Override
    protected ItemStack icon(Guild guild) {
        boolean mine = guild.isMember(viewer.getUniqueId());
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Level <white>" + services.guilds().level(guild) + "<gray>, <white>" + guild.size() + "/"
                + services.guilds().memberCap(guild) + "<gray> members");
        lore.add("<gray>Led by <white>" + GuildMenu.escaped(services.notifier().name(guild.leader())));
        lore.add("<gray>Joining: <white>" + guild.joinMode().title());
        if (!guild.motd().isBlank()) {
            lore.add("<gray>“" + GuildMenu.escaped(guild.motd()) + "”");
        }
        lore.add("");
        lore.add(mine ? "<green>Your guild." : guild.joinMode() == JoinMode.OPEN ? "<gray>Click to join."
                : guild.joinMode() == JoinMode.APPLY ? "<gray>Click to apply." : "<gray>By invitation only.");
        return Icons.of(guild.colour().bannerMaterial(), "<white>" + GuildMenu.escaped(guild.emblem().prefix() + guild.name())
                + " <gray>[" + guild.tag() + "]", lore);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No guilds yet", "<gray>Found the first one from the page before.");
    }

    @Override
    protected void onClick(Guild guild, InventoryClickEvent event) {
        if (guild.isMember(viewer.getUniqueId())) {
            new GuildMenu(services, viewer, this).open();
            return;
        }
        services.messages().send(viewer, services.guilds().request(viewer.getUniqueId(), guild.id()).messageKey());
        refresh();
    }

    @Override
    public String describe() {
        return "every guild, highest level first";
    }
}
