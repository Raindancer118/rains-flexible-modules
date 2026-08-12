package de.raindancer.modules.moderation;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Opening a screen, as something the rest of the module can ask for without knowing what a screen is.
 *
 * <h2>Why not just call the menu classes</h2>
 * Because then every listener, service and command would depend on the screens, and the screens depend
 * on all of them — the cycle that makes a plugin impossible to take apart. This is the one edge that
 * breaks it: the commands ask for a screen, and only the module's own wiring knows which class that is.
 *
 * <p>It is also what lets the screens be rebuilt without touching a line of the logic underneath.
 *
 * <h2>Only the ways in</h2>
 * Every method here is a page something opens <em>from a command</em>, where there is nothing to go
 * back to. A screen opening another screen does it directly and hands itself over as the parent, or
 * Core paints no Back button and the player's only way out is Close — which is what happened when this
 * interface also carried the screen-to-screen jumps.
 */
public interface ModerationScreensOpener {

    /** Everything about one player: their state, their record, their notes, and what may be done. */
    void player(Player viewer, UUID subject, String subjectName);

    /** The same, for somebody the server already knows about. */
    void player(Player viewer, OfflinePlayer subject);

    /** Choosing who to look at — the whole directory, online first. */
    void pickPlayer(Player viewer);

    /** What the staff have written about somebody. */
    void notes(Player viewer, UUID subject, String subjectName);

    /** The report queue. */
    void reports(Player viewer);

    /** Who is on, who is vanished, and who is in staff chat. */
    void staff(Player viewer);

    /** What a player is reporting somebody for — the categories, before the detail. */
    void reportCategories(Player viewer, UUID subject, String subjectName);

    /**
     * Who a player is reporting.
     *
     * <p>The one screen in this module a player without any permission opens, so it shows who is
     * <em>here</em> rather than everybody the server has ever seen — reporting somebody who logged off
     * last March is not a thing anybody needs, and the whole directory is a list a player should not be
     * handed.
     */
    void pickSomebodyToReport(Player viewer);

    /** The world tools: ore veins, packs and waves. */
    void worldTools(Player viewer);

    /** Everybody this server has learnt anything about, ranked by how worth checking they look. */
    void xraySuspicion(Player viewer);
}
