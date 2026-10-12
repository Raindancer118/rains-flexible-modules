package de.raindancer.modules.guilds.screen;

import de.raindancer.core.platform.util.Times;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.Invitation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.List;

/** The invitations waiting for the viewer: click accepts, right click declines. */
public final class InvitationsMenu extends PaginatedMenu<Invitation> implements IGuildsScreen {

    private final GuildsServices services;

    public InvitationsMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Your invitations", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Invitations";
    }

    @Override
    protected List<Invitation> entries() {
        return services.guilds().invitationsOf(viewer.getUniqueId());
    }

    @Override
    protected ItemStack icon(Invitation invitation) {
        Guild guild = services.book().guild(invitation.guild()).orElse(null);
        String name = guild == null ? invitation.guild() : guild.name();
        long left = Math.max(0, invitation.expires() - System.currentTimeMillis());
        return Icons.of(guild == null ? Material.PAPER : guild.colour().bannerMaterial(),
                "<white>" + GuildMenu.escaped(name),
                "<gray>From <white>" + GuildMenu.escaped(services.notifier().name(invitation.by())),
                "<gray>Runs out in <white>" + Times.describe(Duration.ofMillis(left)),
                "", "<gray>Click to join.", "<gray>Right click to decline.");
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No invitations", "<gray>Somebody in a guild can invite you.");
    }

    @Override
    protected void onClick(Invitation invitation, InventoryClickEvent event) {
        TeamId id = new TeamId(invitation.guild());
        if (event.isRightClick()) {
            IGuildsScreen.tell(services, viewer, this, services.guilds().declineInvitation(viewer.getUniqueId(), id));
            return;
        }
        services.messages().send(viewer, services.guilds().acceptInvitation(viewer.getUniqueId(), id).messageKey());
        GuildMenu.forViewer(services, viewer).open();
    }

    @Override
    public String describe() {
        return "invitations waiting for the viewer";
    }
}
