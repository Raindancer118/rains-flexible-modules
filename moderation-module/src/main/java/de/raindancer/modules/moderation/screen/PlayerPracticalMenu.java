package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.invsee.Access;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.OverviewAccess;
import de.raindancer.modules.moderation.model.PlayerOverview;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * The practical things about a player, none of them a punishment: their inventory, going to them, their
 * fines, the tools a moderator flips while working, and their staff rank.
 *
 * <pre>
 *  row 0   ·  ·  ·  [status] [head] [standing]  ·  ·  ·
 *  row 1   Reach them   [inventory]  ·  [go to them]
 *  row 2   Money             [fines]
 *  row 3   Staff        [tools]  ·  [staff rank]
 * </pre>
 */
public final class PlayerPracticalMenu extends PlayerScreen {

    public PlayerPracticalMenu(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName) {
        super(services, viewer, parent, subject, subjectName);
    }

    @Override
    protected String room() {
        return "Practical";
    }

    @Override
    protected void render() {
        header();
        group(MenuLayout.WHO, Material.LIME_STAINED_GLASS_PANE, "<green>Reach them");
        group(MenuLayout.RULES, Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Money");
        group(MenuLayout.LAND, Material.PURPLE_STAINED_GLASS_PANE, "<light_purple>Staff");

        PlayerOverview seen = seen();
        OverviewAccess access = access();

        band(MenuLayout.WHO, 3, may(ModerationPermission.INVSEE),
                Icons.of(Material.CHEST, "<yellow>Inventory & ender chest",
                        may(ModerationPermission.INVSEE_EDIT) ? "<gray>You may change what is in it."
                                : "<gray>Looking only.", "<dark_gray>Offline too."),
                "For whoever may look in inventories",
                click -> lookInside());
        boolean here = seen.where() != null;
        band(MenuLayout.WHO, 5, here && viewer.hasPermission(TELEPORT),
                Icons.of(Material.ENDER_PEARL, "<yellow>Go to them",
                        here && access.location() ? "<gray>" + seen.where().world() + " " + seen.where().x() + " "
                                + seen.where().y() + " " + seen.where().z()
                                : here ? "<gray>To where they stand." : "<gray>Only while they are online."),
                here ? "For whoever may teleport" : "They are not online",
                click -> goTo());
        band(MenuLayout.RULES, 4, may(ModerationPermission.HISTORY),
                Icons.of(Material.GOLD_NUGGET, "<yellow>Fines", access.record() ? "<gray>Owes <white>"
                        + de.raindancer.core.social.economy.Fees.format(services().fines().owed(subject))
                        + "</white>." : "<gray>What they were fined.", "<dark_gray>Revoke one, or forgive the debt."),
                "For whoever may read a record",
                click -> new FinesMenu(services(), viewer, this, subject, subjectName).open());
        // Flight, invulnerability, one-hit-kill: none is a punishment, so they live apart from them.
        band(MenuLayout.LAND, 3, may(ModerationPermission.FLY) || may(ModerationPermission.GOD)
                        || may(ModerationPermission.INSTAKILL),
                Icons.of(Material.FEATHER, "<yellow>Tools", "<gray>Flight, invulnerability,",
                        "<gray>heal, feed, one-hit-kill."),
                "For whoever holds one of those",
                click -> new ToolsMenu(services(), viewer, this, subject, subjectName).open());
        // Drawn for everybody, greyed for anybody but the owner: ranks exist, and are not theirs to give.
        band(MenuLayout.LAND, 5, viewer.isOp()
                        || viewer.hasPermission(de.raindancer.modules.moderation.command.PromoteCommand.USE),
                Icons.of(Material.IRON_HELMET, "<yellow>Staff rank", "<gray>" + services().roster().rankOf(subject)
                        .map(rank -> rank.title()).orElse("Not staff") + ".", "<dark_gray>Promote, demote, permissions."),
                "Only the server owner may hand out ranks",
                click -> new RankMenu(services(), viewer, this, subject, subjectName).open());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>None of these is a punishment:",
                "<gray>inventory, going to them, fines, tools, rank.");
    }

    /** To where they stand, read on their thread and gone to on ours. */
    private void goTo() {
        Player target = services().server().getPlayer(subject);
        if (target == null) {
            tell("moderation.not-here", "player", subjectName);
            return;
        }
        if (!viewer.hasPermission(TELEPORT)) {
            tell("moderation.no-permission");
            return;
        }
        viewer.closeInventory();
        Scheduling.entity(services().plugin(), target, () -> {
            org.bukkit.Location at = target.getLocation();
            Scheduling.entity(services().plugin(), viewer, () -> viewer.teleportAsync(at));
        });
    }

    /**
     * Opens Core's inventory window. Editing needs the wider grant, because taking somebody's helmet off by
     * clicking one slot too far is the mistake {@code Access} was split up to prevent.
     */
    private void lookInside() {
        Access wanted = may(ModerationPermission.INVSEE_EDIT) ? Access.EDIT : Access.READ_ONLY;
        services().inventories().open(viewer, subject, subjectName, wanted,
                outcome -> {
                    if (!outcome.opened()) {
                        tell("moderation.invsee.refused", "reason", outcome.saying());
                    }
                });
    }

    @Override
    public String describe() {
        return "the practical things about a player: inventory, going to them, fines, tools, staff rank";
    }
}
