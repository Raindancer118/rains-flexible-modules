package de.raindancer.modules.guilds.screen;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.ui.choose.OptionChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildPermission;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The guild's own choices. Who may change them is a rank's EDIT_INFO; friendly fire and disbanding are the leader's. */
public final class GuildSettingsMenu extends Menu implements IGuildsScreen {

    private final GuildsServices services;

    public GuildSettingsMenu(GuildsServices services, Player viewer, Menu parent) {
        super(viewer, services.brand(), parent);
        this.services = services;
    }

    @Override
    protected Component title() {
        return Component.text("The guild's choices", NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return "Choices";
    }

    @Override
    protected void render() {
        Optional<Guild> found = services.guilds().guildOf(viewer.getUniqueId());
        if (found.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>You are in no guild now"));
            return;
        }
        Guild guild = found.get();
        UUID me = viewer.getUniqueId();
        boolean edit = guild.may(me, GuildPermission.EDIT_INFO);
        boolean leader = guild.leader().equals(me);
        String locked = "Your rank cannot change the guild.";

        band(MenuLayout.WHO, 1, edit, Icons.of(Material.NAME_TAG, "<white>Name",
                        "<gray>" + GuildMenu.escaped(guild.name()), "", "<gray>Click, then type a new name."), locked,
                click -> IGuildsScreen.ask(services, viewer, this, "guilds.ask.name",
                        typed -> services.messages().send(viewer, services.guilds().rename(me, typed).messageKey())));
        band(MenuLayout.WHO, 3, edit, Icons.of(Material.OAK_SIGN, "<white>Tag",
                        "<gray>[" + guild.tag() + "]", "", "<gray>Click, then type a new tag."), locked,
                click -> IGuildsScreen.ask(services, viewer, this, "guilds.ask.tag",
                        typed -> services.messages().send(viewer, services.guilds().retag(me, typed).messageKey())));
        band(MenuLayout.WHO, 5, edit, Icons.of(guild.colour().bannerMaterial(), "<white>Colour",
                        "<gray>" + guild.colour().describe(), "", "<gray>Click to pick one."), locked,
                click -> new OptionChooser(viewer, services.brand(), this, "Colour",
                        Arrays.stream(TeamColour.values()).map(TeamColour::describe).toList(), guild.colour().describe(), chosen -> {
                    Arrays.stream(TeamColour.values()).filter(colour -> colour.describe().equals(chosen)).findFirst()
                            .ifPresent(colour -> services.messages().send(viewer, services.guilds().look(me, colour, null).messageKey()));
                    open();
                }).open());
        band(MenuLayout.WHO, 7, edit, Icons.of(guild.emblem().suggestedBadge(), "<white>Emblem",
                        "<gray>" + guild.emblem().describe(), "", "<gray>Click to pick one."), locked,
                click -> new OptionChooser(viewer, services.brand(), this, "Emblem",
                        Arrays.stream(TeamEmblem.values()).map(TeamEmblem::describe).toList(), guild.emblem().describe(), chosen -> {
                    Arrays.stream(TeamEmblem.values()).filter(emblem -> emblem.describe().equals(chosen)).findFirst()
                            .ifPresent(emblem -> services.messages().send(viewer, services.guilds().look(me, null, emblem).messageKey()));
                    open();
                }).open());

        band(MenuLayout.RULES, 2, edit, Icons.of(Material.WRITABLE_BOOK, "<white>Message of the day",
                        guild.motd().isBlank() ? "<gray>None." : "<gray>“" + GuildMenu.escaped(guild.motd()) + "”",
                        "", "<gray>Click, then type it.", "<gray>Right click to clear it."), locked,
                click -> {
                    if (click.isRightClick()) {
                        IGuildsScreen.tell(services, viewer, this, services.guilds().motd(me, ""));
                        return;
                    }
                    IGuildsScreen.ask(services, viewer, this, "guilds.ask.motd",
                            typed -> services.messages().send(viewer, services.guilds().motd(me, typed).messageKey()));
                });
        band(MenuLayout.RULES, 4, edit, Icons.of(guild.joinMode().icon(), "<white>Who may join",
                        "<gray>" + guild.joinMode().title(), "", "<gray>Click to change."), locked,
                click -> IGuildsScreen.tell(services, viewer, this, services.guilds().joinMode(me, guild.joinMode().next())));
        band(MenuLayout.RULES, 6, leader, Icons.of(Material.IRON_SWORD, "<white>Friendly fire",
                        "<gray>Members hurting each other: " + (guild.friendlyFire() ? "<red>on" : "<green>off"),
                        "<gray>Members hurting allies: " + (guild.allyFriendlyFire() ? "<red>on" : "<green>off"),
                        "", "<gray>Click to switch it for members.", "<gray>Right click to switch it for allies.",
                        "<dark_gray>Only ever stops damage; it never allows", "<dark_gray>PvP where the server does not."),
                "Only the leader decides this.",
                click -> IGuildsScreen.tell(services, viewer, this, click.isRightClick()
                        ? services.guilds().friendlyFire(me, guild.friendlyFire(), !guild.allyFriendlyFire())
                        : services.guilds().friendlyFire(me, !guild.friendlyFire(), guild.allyFriendlyFire())));

        if (leader) {
            danger(Icons.of(Material.TNT, "<red>Disband the guild",
                            "<gray>The bank is shared out first.", "", "<gray>You will be asked first."),
                    click -> new ConfirmScreen(services, viewer, this, "<red>Disband " + GuildMenu.escaped(guild.name()) + "?",
                            List.of("<gray>Every member leaves, every alliance", "<gray>ends, and the name is free again."),
                            () -> {
                                services.messages().send(viewer, services.guilds()
                                        .disband(me, guild.id(), false, services.bank()).messageKey());
                                viewer.closeInventory();
                            }).open());
        } else {
            band(MenuLayout.LAND, 4, Icons.locked(Icons.of(Material.TNT, "<white>Disband the guild"), "Only the leader can disband it."));
        }
    }

    @Override
    public String describe() {
        return "the guild's own choices";
    }
}
