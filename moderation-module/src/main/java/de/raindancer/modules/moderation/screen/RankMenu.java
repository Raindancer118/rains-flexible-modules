package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.command.PromoteCommand;
import de.raindancer.modules.moderation.model.StaffRank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Making somebody staff, and choosing which tier.
 *
 * <h2>Why the tiers are buttons and not a list</h2>
 * Four things, each of which is a decision somebody states out loud. A paged list would put them in a
 * grid that reads as a catalogue, and the point of a ladder is that it is a ladder — cheapest on the
 * left, and each one visibly containing the last.
 *
 * <h2>What the ops-only check is doing on a screen</h2>
 * The same thing it does on the command: a power that hands out powers must not be one of the powers it
 * hands out. Every button here is greyed for anybody who is not op, with the reason — rather than the
 * page being absent, so a moderator who wonders how promotion works can see that it exists and that it
 * is not theirs.
 */
public final class RankMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;

    public RankMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                    String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Rank — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "Rank";
    }

    /** Whether this viewer may hand out ranks at all. Op, or the node — never a moderation preset. */
    private boolean mayPromote() {
        return viewer.isOp() || viewer.hasPermission(PromoteCommand.USE);
    }

    @Override
    protected void render() {
        Optional<StaffRank> current = services().roster().rankOf(subject);
        boolean allowed = mayPromote();

        band(MenuLayout.WHO, 4, Icons.head(subject, "<white>" + subjectName, whereTheyStand(current)));

        // ── the ladder ────────────────────────────────────────────────────────────────────────
        int column = 2;
        for (StaffRank rank : StaffRank.values()) {
            boolean theirs = current.filter(held -> held == rank).isPresent();
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + rank.describe());
            lore.add("");
            lore.add("<dark_gray>" + rank.nodes().size() + " permission(s)");
            if (theirs) {
                lore.add("<green>This is their rank now.");
            } else {
                lore.add("<dark_gray>Click to make them a " + rank.title().toLowerCase(
                        java.util.Locale.ROOT) + ".");
            }

            band(MenuLayout.RULES, column++, allowed && !theirs,
                    Icons.of(rank.icon(), "<" + rank.colour() + ">" + rank.title(), lore),
                    theirs ? "Already their rank" : "Only the server owner may hand out ranks",
                    click -> confirm(rank));
        }

        // ── the exception, and putting it back ────────────────────────────────────────────────
        if (current.isPresent()) {
            band(MenuLayout.LAND, 3, allowed,
                    Icons.of(Material.COMPARATOR, "<yellow>Their permissions one by one",
                            "<gray>Toggle any single one without changing their rank.",
                            drift(current.get())),
                    "Only the server owner may change permissions",
                    click -> new PermissionsMenu(services(), viewer, this, subject, subjectName)
                            .open());

            band(MenuLayout.LAND, 5, allowed && !services().roster().matchesPreset(subject),
                    Icons.of(Material.STRUCTURE_VOID, "<yellow>Put the preset back",
                            "<gray>Undoes every individual change.",
                            "<dark_gray>They end up exactly as a "
                                    + current.get().title().toLowerCase(java.util.Locale.ROOT)
                                    + " again."),
                    services().roster().matchesPreset(subject)
                            ? "Nothing has been changed by hand"
                            : "Only the server owner may change permissions",
                    click -> {
                        services().staff().reapplyPreset(viewer, subject, subjectName);
                        tell("moderation.rank.preset-restored", "player", subjectName);
                        open();
                    });

            // The one irreversible thing on this page: off the staff entirely.
            if (allowed) {
                danger(Icons.of(Material.BARRIER, "<red>Off the staff",
                                "<gray>Takes the rank and every permission with it.",
                                "<dark_gray>Asks first."),
                        click -> new ConfirmScreen(services(), viewer, this,
                                "<red>Take " + subjectName + " off the staff?",
                                List.of("<gray>They lose every permission this granted.",
                                        "<gray>Nothing on anybody's record changes.",
                                        "<dark_gray>They can be promoted again at any time."),
                                this::takeThemOff).open());
            }
        }
    }

    private List<String> whereTheyStand(Optional<StaffRank> current) {
        List<String> lore = new ArrayList<>();
        current.ifPresentOrElse(
                rank -> {
                    lore.add("<" + rank.colour() + ">" + rank.title());
                    lore.add("<gray>" + services().grants().countFor(subject) + " permission(s) held.");
                    lore.add(drift(rank));
                },
                () -> {
                    lore.add("<gray>Not staff.");
                    lore.add("<dark_gray>Pick a rank below.");
                });
        return lore;
    }

    /** One line saying whether their permissions still match their rank. */
    private String drift(StaffRank rank) {
        int extra = services().roster().extraNodes(subject).size();
        int missing = services().roster().missingNodes(subject).size();
        if (extra == 0 && missing == 0) {
            return "<dark_gray>Exactly what a " + rank.title().toLowerCase(java.util.Locale.ROOT)
                    + " gets.";
        }
        List<String> parts = new ArrayList<>();
        if (extra > 0) {
            parts.add(extra + " extra");
        }
        if (missing > 0) {
            parts.add(missing + " taken away");
        }
        return "<yellow>Changed by hand: " + String.join(", ", parts) + ".";
    }

    private void confirm(StaffRank rank) {
        Optional<StaffRank> current = services().roster().rankOf(subject);
        boolean down = current.isPresent() && !rank.isAtLeast(current.get());

        List<String> what = new ArrayList<>();
        what.add("<gray>" + rank.describe());
        what.add("<gray>They get <white>" + rank.nodes().size() + "</white> permission(s).");
        if (down) {
            what.add("<yellow>This is a step down from "
                    + current.get().title() + " — they lose what it had.");
        }
        if (rank == StaffRank.ADMIN) {
            what.add("<red>An admin can change the settings and cannot be acted on by moderators.");
        }

        new ConfirmScreen(services(), viewer, this,
                "<yellow>Make " + subjectName + " a " + rank.title().toLowerCase(
                        java.util.Locale.ROOT) + "?",
                what,
                () -> {
                    if (!mayPromote()) {
                        // Re-asked at the click: the page was rendered at least two clicks ago, and op
                        // can be taken away in between.
                        tell("moderation.rank.not-yours");
                        return;
                    }
                    services().staff().promote(viewer, subject, subjectName, rank);
                    open();
                }).open();
    }

    private void takeThemOff() {
        if (!mayPromote()) {
            tell("moderation.rank.not-yours");
            return;
        }
        services().staff().demote(viewer, subject, subjectName);
        open();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Each rank contains everything the one below it has.",
                "<gray>Only the server owner can hand out ranks — not even an admin can.",
                "<gray>Single permissions can be toggled without changing somebody's rank.");
    }

    @Override
    public String describe() {
        return "somebody's staff rank: the four tiers, and the way to a single permission";
    }
}
