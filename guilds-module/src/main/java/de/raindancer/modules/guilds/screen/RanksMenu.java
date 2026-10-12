package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** The ladder, most senior first. Click a rank for what it may do; a new one is made from the toolbar. */
public final class RanksMenu extends PaginatedMenu<Rank> implements IGuildsScreen {

    private final GuildsServices services;

    public RanksMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    private Optional<Guild> guild() {
        return services.guilds().guildOf(viewer.getUniqueId());
    }

    @Override
    protected Component title() {
        return Component.text("Ranks", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Ranks";
    }

    @Override
    protected List<Rank> entries() {
        return guild().map(Guild::ranks).orElse(List.of());
    }

    @Override
    protected ItemStack icon(Rank rank) {
        Guild guild = guild().orElseThrow();
        long holders = guild.members().values().stream().filter(member -> member.rank().equals(rank.id())).count();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + holders + " member(s).");
        lore.add(rank.isUnlimited() ? "<gray>No daily bank limit." : "<gray>Daily bank limit: <white>" + services.bank().format(rank.dailyLimit()));
        lore.add("");
        for (GuildPermission permission : GuildPermission.values()) {
            lore.add((rank.may(permission) ? "<green>✔ " : "<dark_gray>✘ ") + permission.title());
        }
        lore.add("");
        lore.add("<gray>Click for more.");
        return Icons.of(rank.isLeader() ? Material.GOLDEN_HELMET : Material.LEATHER_HELMET,
                "<white>" + GuildMenu.escaped(rank.name()), lore);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<red>You are in no guild now");
    }

    @Override
    protected void onClick(Rank rank, InventoryClickEvent event) {
        new RankMenu(services, viewer, this, rank.id()).open();
    }

    @Override
    protected void render() {
        super.render();
        boolean may = guild().map(guild -> guild.may(viewer.getUniqueId(), GuildPermission.MANAGE_RANKS)).orElse(false);
        toolbar(4, may, Icons.of(Material.LADDER, "<white>New rank",
                        "<gray>Starts just above the lowest,", "<gray>with nothing allowed.", "", "<gray>Click, then type its name."),
                "Your rank cannot manage ranks.",
                click -> IGuildsScreen.ask(services, viewer, this, "guilds.ask.rank-name",
                        typed -> services.messages().send(viewer, services.guilds().createRank(viewer.getUniqueId(), typed).messageKey())));
    }

    @Override
    public String describe() {
        return "the guild's ranks";
    }
}
