package de.raindancer.modules.moderation.screen;

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
 * What a player has done: their record and everything written or kept about them. Each door is drawn for
 * everybody and greyed, with the reason, for whoever may not open it.
 *
 * <pre>
 *  row 0   ·  ·  [record] [status] [head] [standing] [x-ray]  ·  ·
 *  row 1   Record              [record]  ·  [audit trail]  ·  [attempts]
 *  row 2   Reports & evidence        [reports]  ·  [mining]
 *  row 3   What was said             [chat]  ·  [staff notes]
 * </pre>
 */
public final class PlayerLookIntoMenu extends PlayerScreen {

    public PlayerLookIntoMenu(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName) {
        super(services, viewer, parent, subject, subjectName);
    }

    @Override
    protected String room() {
        return "Look into";
    }

    @Override
    protected void render() {
        header();
        group(MenuLayout.WHO, Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Record");
        group(MenuLayout.RULES, Material.ORANGE_STAINED_GLASS_PANE, "<gold>Reports & evidence");
        group(MenuLayout.LAND, Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<aqua>What was said");

        PlayerOverview seen = seen();
        OverviewAccess access = access();
        set(PRESENCE - 1, Icons.of(Material.WRITABLE_BOOK, "<yellow>Record", seen.recordLore(access)));
        set(STANDING + 1, Icons.of(Material.DIAMOND_ORE, "<yellow>X-ray", seen.xrayLore(access)));

        band(MenuLayout.WHO, 2, may(ModerationPermission.HISTORY),
                Icons.of(Material.BOOK, "<yellow>Record", access.record() ? "<gray>" + seen.recordEntries()
                        + " entries, every kind." : "<gray>Every kind of entry.", "<dark_gray>Newest first."),
                "For whoever may read a record",
                click -> new HistoryMenu(services(), viewer, this, subject, subjectName).open());
        band(MenuLayout.RULES, 3, may(ModerationPermission.REPORTS),
                Icons.of(Material.PAPER, "<yellow>Reports",
                        access.reports() ? "<gray>" + seen.openReports() + " open of " + seen.reports() + " about them."
                                : "<gray>Reports about them."),
                "For whoever may read reports",
                click -> new ReportsMenu(services(), viewer, this).open());
        band(MenuLayout.RULES, 5, may(ModerationPermission.REPORTS),
                Icons.of(Material.DIAMOND_PICKAXE, "<yellow>Mining evidence",
                        access.reports() ? "<gray>" + seen.xray().finds() + " vein(s) and bait(s) on record."
                                : "<gray>Veins and baits on record.",
                        "<dark_gray>The x-ray tests, and where and when each find was."),
                "For whoever may read reports",
                click -> new XrayReviewMenu(services(), viewer, this, subject, subjectName).open());
        band(MenuLayout.WHO, 6, may(ModerationPermission.ATTEMPTS),
                Icons.of(Material.TRIPWIRE_HOOK, "<yellow>Attempts while punished",
                        access.attempts() ? "<gray>" + seen.attempts() + " kept: joins, chat, building."
                                : "<gray>Joins, chat, building while punished.",
                        "<dark_gray>/attempts searches everybody's."),
                "For whoever may see attempts",
                click -> new AttemptsMenu(services(), viewer, this, subject, subjectName).open());

        band(MenuLayout.LAND, 3, may(ModerationPermission.CHATLOG),
                Icons.of(Material.OAK_HANGING_SIGN, "<yellow>Chat", "<gray>Their public chat, newest first,",
                        "<gray>and a search through it."),
                "For whoever may read the chat log",
                click -> new ChatLogMenu(services(), viewer, this, subject, subjectName, "").open());
        band(MenuLayout.LAND, 5, may(ModerationPermission.NOTES),
                Icons.of(Material.WRITABLE_BOOK, "<yellow>Staff notes",
                        access.notes() ? "<gray>" + seen.notes() + " written." : "<gray>What staff wrote.",
                        "<dark_gray>They never see these."),
                "For whoever may read the notes",
                click -> new NotesMenu(services(), viewer, this, subject, subjectName).open());
        band(MenuLayout.WHO, 4, may(ModerationPermission.HISTORY),
                Icons.of(Material.OAK_SIGN, "<yellow>Audit trail", "<gray>Joins, teleports, gamemode,",
                        "<gray>every moderation action.", "<dark_gray>Newest first."),
                "For whoever may read a record",
                click -> new AuditMenu(services(), viewer, this, subject, subjectName).open());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Everything on record about them,",
                "<gray>and what staff wrote and kept.");
    }

    @Override
    public String describe() {
        return "what a player has done: record, reports, mining evidence, attempts, chat, notes, audit";
    }
}
