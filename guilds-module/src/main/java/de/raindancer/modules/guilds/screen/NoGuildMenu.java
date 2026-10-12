package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** For somebody in no guild: found one, look at those there are, answer invitations. */
public final class NoGuildMenu extends Menu implements IGuildsScreen {

    private final GuildsServices services;

    public NoGuildMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Guilds", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Guilds";
    }

    @Override
    protected void render() {
        if (services.guilds().guildOf(viewer.getUniqueId()).isPresent()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.LIME_BANNER, "<green>You are in a guild now",
                    "", "<gray>Click to open its page."), click -> new GuildMenu(services, viewer, null).open());
            return;
        }
        String price = services.bank().format(services.guilds().foundingPrice().minor());
        boolean mayFound = viewer.hasPermission(PermissionNodes.FOUND);
        band(MenuLayout.WHO, 2, mayFound, Icons.of(Material.BEACON, "<white>Found a guild",
                        services.guilds().foundingPrice().isPositive() ? "<gray>Costs <white>" + price + "<gray>." : "<gray>Free.",
                        "<dark_gray>" + services.guilds().names().describe() + ".",
                        "", "<gray>Click, then type the name and the tag", "<gray>in chat: <white>The Elves ELF"),
                "Founding guilds is not for you on this server.",
                click -> IGuildsScreen.ask(services, viewer, null, "guilds.ask.found", typed -> {
                    int space = typed.lastIndexOf(' ');
                    if (space <= 0) {
                        services.messages().send(viewer, "guilds.usage.found");
                        return;
                    }
                    services.messages().send(viewer, services.guilds()
                            .found(viewer.getUniqueId(), typed.substring(0, space), typed.substring(space + 1)).messageKey());
                }));
        band(MenuLayout.WHO, 4, Icons.of(Material.COMPASS, "<white>Every guild",
                        "<gray>" + services.book().count() + " on this server.",
                        "", "<gray>Click to look through them and join", "<gray>or apply to one."),
                click -> new BrowseMenu(services, viewer, this).open());
        int invitations = services.guilds().invitationsOf(viewer.getUniqueId()).size();
        band(MenuLayout.WHO, 6, Icons.of(invitations > 0 ? Material.FILLED_MAP : Material.MAP, "<white>Your invitations",
                        "<gray>" + invitations + " waiting.", "", "<gray>Click to answer them."),
                click -> new InvitationsMenu(services, viewer, this).open());
    }

    @Override
    public String describe() {
        return "the guild page for somebody in no guild";
    }
}
