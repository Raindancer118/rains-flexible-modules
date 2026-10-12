package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.choose.PlayerChooser;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Applications to the guild — click accepts, right click declines — and inviting somebody new. */
public final class RecruitMenu extends PaginatedMenu<Invitation> implements IGuildsScreen {

    private final GuildsServices services;

    public RecruitMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("Invite and applications", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Recruiting";
    }

    @Override
    protected List<Invitation> entries() {
        return services.guilds().guildOf(viewer.getUniqueId()).map(services.guilds()::applicationsTo).orElse(List.of());
    }

    @Override
    protected ItemStack icon(Invitation application) {
        return Icons.head(application.subject(), "<white>" + GuildMenu.escaped(services.notifier().name(application.subject())),
                "<gray>Wants to join.", "", "<gray>Click to let them in.", "<gray>Right click to decline.");
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.COBWEB, "<gray>No applications", "<gray>Set the guild to take applications",
                "<gray>on its choices page, or invite somebody below.");
    }

    @Override
    protected void onClick(Invitation application, InventoryClickEvent event) {
        UUID me = viewer.getUniqueId();
        IGuildsScreen.tell(services, viewer, this, event.isRightClick()
                ? services.guilds().declineApplication(me, application.subject())
                : services.guilds().acceptApplication(me, application.subject()));
    }

    @Override
    protected void render() {
        super.render();
        toolbar(4, Icons.of(Material.WRITABLE_BOOK, "<white>Invite somebody", "", "<gray>Click to pick a player."),
                click -> {
                    List<UUID> exclude = new ArrayList<>(services.guilds().guildOf(viewer.getUniqueId())
                            .map(Guild::members).map(members -> members.keySet()).orElse(java.util.Set.of()));
                    new PlayerChooser(viewer, services.brand(), this, "Invite to the guild", exclude, chosen -> {
                        services.messages().send(viewer, services.guilds().invite(viewer.getUniqueId(), chosen.id()).messageKey());
                        open();
                    }).open();
                });
    }

    @Override
    public String describe() {
        return "applications to the guild, and inviting somebody";
    }
}
