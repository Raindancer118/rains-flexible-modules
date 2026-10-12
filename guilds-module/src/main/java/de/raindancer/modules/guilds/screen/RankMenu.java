package de.raindancer.modules.guilds.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.guilds.GuildsServices;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One rank: a switch per permission in a grid, then its name, its bank limit and its place on the ladder. Only a
 * rank below the viewer's is editable, and only with permissions the viewer holds — the rest is greyed with why.
 */
public final class RankMenu extends Menu implements IGuildsScreen {

    private final GuildsServices services;
    private final String rankId;

    public RankMenu(GuildsServices services, Player viewer, Menu parent, String rankId) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.rankId = rankId;
    }

    private Optional<Guild> guild() {
        return services.guilds().guildOf(viewer.getUniqueId());
    }

    @Override
    protected Component title() {
        return Component.text(guild().flatMap(guild -> guild.rank(rankId)).map(Rank::name).orElse("Rank"), NamedTextColor.DARK_GRAY);
    }

    @Override
    public String breadcrumb() {
        return guild().flatMap(guild -> guild.rank(rankId)).map(Rank::name).orElse("Rank");
    }

    private static String why(GuildOutcome outcome) {
        return switch (outcome) {
            case LEADER_RANK -> "The leader may always do everything.";
            case OUTRANKED -> "Only ranks below your own can be changed.";
            case PERMISSION_NOT_HELD -> "You cannot hand out what you do not have.";
            default -> "Your rank cannot manage ranks.";
        };
    }

    @Override
    protected void render() {
        Optional<Guild> found = guild();
        Optional<Rank> current = found.flatMap(guild -> guild.rank(rankId));
        if (current.isEmpty()) {
            band(MenuLayout.WHO, 4, Icons.of(Material.BARRIER, "<red>This rank is gone"));
            return;
        }
        Guild guild = found.get();
        Rank rank = current.get();
        UUID me = viewer.getUniqueId();
        GuildPermission[] all = GuildPermission.values();
        for (int index = 0; index < all.length; index++) {
            GuildPermission permission = all[index];
            boolean on = rank.may(permission);
            GuildOutcome may = services.guilds().ranks().mayGrant(guild, me, rank, permission);
            var icon = Icons.of(on ? permission.icon() : Material.GRAY_DYE,
                    (on ? "<green>" : "<gray>") + permission.title(), "<gray>" + permission.description(),
                    "", on ? "<green>Allowed. <gray>Click to take it away." : "<red>Not allowed. <gray>Click to allow it.");
            cell(1 + index / 7, 1 + index % 7, may.isSuccess() ? icon : Icons.locked(icon, why(may)),
                    may.isSuccess() ? click -> IGuildsScreen.tell(services, viewer, this, services.guilds().toggle(me, rankId, permission))
                            : click -> services.messages().send(viewer, may.messageKey()));
        }
        GuildOutcome edit = services.guilds().ranks().mayEdit(guild, me, rank);
        toolbar(1, edit.isSuccess(), Icons.of(Material.NAME_TAG, "<white>Rename", "", "<gray>Click, then type the new name."),
                why(edit), click -> IGuildsScreen.ask(services, viewer, this, "guilds.ask.rank-name",
                        typed -> services.messages().send(viewer, services.guilds().renameRank(me, rankId, typed).messageKey())));
        toolbar(2, edit.isSuccess(), Icons.of(Material.GOLD_NUGGET, "<white>Daily bank limit",
                        rank.isUnlimited() ? "<gray>None." : "<gray>" + services.bank().format(rank.dailyLimit()),
                        "", "<gray>Click, then type an amount.", "<gray>Type <white>none<gray> for no limit."),
                why(edit), click -> IGuildsScreen.ask(services, viewer, this, "guilds.ask.limit", typed -> {
                    long minor = typed.equalsIgnoreCase("none") ? -1
                            : services.bank().parse(typed).map(money -> money.minor()).orElse(typed.equals("0") ? 0L : Long.MIN_VALUE);
                    services.messages().send(viewer, minor == Long.MIN_VALUE ? GuildOutcome.INVALID_AMOUNT.messageKey()
                            : services.guilds().limitRank(me, rankId, minor).messageKey());
                }));
        toolbar(3, edit.isSuccess(), Icons.of(Material.LIME_DYE, "<white>Move up the ladder", "", "<gray>Click to swap with the rank above."),
                why(edit), click -> IGuildsScreen.tell(services, viewer, this, services.guilds().moveRank(me, rankId, true)));
        toolbar(5, edit.isSuccess(), Icons.of(Material.RED_DYE, "<white>Move down the ladder", "", "<gray>Click to swap with the rank below."),
                why(edit), click -> IGuildsScreen.tell(services, viewer, this, services.guilds().moveRank(me, rankId, false)));
        if (edit.isSuccess()) {
            danger(Icons.of(Material.BARRIER, "<red>Delete this rank", "<gray>Only a rank nobody holds.", "", "<gray>You will be asked first."),
                    click -> new ConfirmScreen(services, viewer, this, "<red>Delete " + GuildMenu.escaped(rank.name()) + "?",
                            List.of("<gray>Its permissions go with it."), () -> {
                        GuildOutcome deleted = services.guilds().deleteRank(me, rankId);
                        services.messages().send(viewer, deleted.messageKey());
                        if (deleted.isSuccess() && parent() != null) {
                            parent().open();
                        } else {
                            open();
                        }
                    }).open());
        }
    }

    @Override
    public String describe() {
        return "one rank's permissions, name, limit and place";
    }
}
