package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.OverviewAccess;
import de.raindancer.modules.moderation.model.PlayerOverview;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One player: who they are at a glance, and three doors.
 *
 * <h2>Why three doors and not every button at once</h2>
 * A moderator opening somebody's page has one of three questions — what has this person done, what do I
 * do about it, or something practical like their inventory. A page with every answer laid out as a table
 * makes them read all of it to find theirs. Each door says on its face what is behind it, with the numbers
 * that matter, so most of the time the hub alone answers "is there anything here at all".
 *
 * <pre>
 *  row 0   ·  ·  ·  [status] [head] [standing]  ·  ·  ·
 *  row 2            [look into]  ·  [punish]  ·  [practical]
 * </pre>
 */
public final class PlayerMenu extends PlayerScreen {

    public PlayerMenu(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName) {
        super(services, viewer, parent, subject, subjectName);
    }

    @Override
    protected String room() {
        return "Moderation";
    }

    @Override
    public String breadcrumb() {
        return subjectName;
    }

    /** The trail already says "Moderation »"; the hub's own title is just who it is about. */
    @Override
    protected net.kyori.adventure.text.Component title() {
        return net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<white>" + subjectName);
    }

    @Override
    protected void render() {
        header();
        PlayerOverview seen = seen();
        OverviewAccess access = access();

        List<String> look = new ArrayList<>();
        if (access.record()) {
            look.add("<gray>" + seen.recordEntries() + " entries on the record.");
        }
        if (access.reports()) {
            look.add("<gray>" + seen.openReports() + " open report(s), " + seen.xray().finds() + " mining find(s).");
        }
        if (access.notes()) {
            look.add("<gray>" + seen.notes() + " staff note(s).");
        }
        look.add("");
        look.add("<dark_gray>Record, reports, mining evidence,");
        look.add("<dark_gray>attempts, chat, notes, audit trail.");
        band(MenuLayout.RULES, 2, Icons.of(Material.SPYGLASS, "<aqua><bold>Look into", look),
                click -> new PlayerLookIntoMenu(services(), viewer, this, subject, subjectName).open());

        List<String> punish = new ArrayList<>();
        punish.add("<gray>" + seen.standing().title().replaceAll("<[^>]+>", "") + ".");
        punish.add("");
        punish.add("<dark_gray>Warn, kick, mute, freeze, ban,");
        punish.add("<dark_gray>fine, broke a rule — or lift one.");
        band(MenuLayout.RULES, 4, Icons.of(Material.IRON_SWORD, "<red><bold>Punish", punish),
                click -> new PlayerPunishMenu(services(), viewer, this, subject, subjectName).open());

        List<String> practical = new ArrayList<>();
        practical.add(seen.where() != null ? "<green>Online now." : "<gray>Not online.");
        practical.add("");
        practical.add("<dark_gray>Inventory, go to them, fines,");
        practical.add("<dark_gray>tools, staff rank.");
        band(MenuLayout.RULES, 6, Icons.of(Material.CHEST, "<green><bold>Practical", practical),
                click -> new PlayerPracticalMenu(services(), viewer, this, subject, subjectName).open());
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Top: online (green), away (orange) or offline (red),",
                "<gray>who they are, what is in force.",
                "<gray>Below: look into · punish · practical.");
    }

    @Override
    public String describe() {
        return "one player at a glance, with a door each to looking into, punishing and practical things";
    }
}
