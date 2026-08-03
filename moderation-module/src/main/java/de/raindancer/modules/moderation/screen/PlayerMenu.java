package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.invsee.Access;
import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Sentence;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One player, and everything a moderator can do about them.
 *
 * <h2>Why there is a hub at all</h2>
 * Because the question a moderator actually has is "what is going on with this person", and answering
 * it from commands means four of them and remembering all four. Everything here is one click from the
 * name: what is in force, what is on the record, what the staff have written, and every button that
 * would change any of it.
 *
 * <h2>Everything is shown, including what this viewer may not press</h2>
 * A trial helper opening this sees the ban button greyed with the reason. That is deliberate: it tells
 * them what exists, what they will be trusted with next, and — the practical half — that the button is
 * there at all, so nobody has to be told "the third one along, unless you cannot see it".
 */
public final class PlayerMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;

    public PlayerMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                      String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName == null || subjectName.isBlank() ? "somebody" : subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Moderation — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return subjectName;
    }

    @Override
    protected void render() {
        // The head in the middle, where the eye lands first: this page is about a person, and the
        // person is the heading. What is in force is on the head itself, so the answer to "what is
        // going on with them" is read without a click.
        band(MenuLayout.WHO, 2, may(ModerationPermission.NOTES),
                Icons.of(Material.WRITABLE_BOOK, "<yellow>Staff notes",
                        "<gray>" + services().noteService().countAbout(subject) + " written.",
                        "<dark_gray>They never see these."),
                "For whoever may read the notes",
                click -> new NotesMenu(services(), viewer, this, subject, subjectName).open());

        band(MenuLayout.WHO, 4, Icons.head(subject, "<white>" + subjectName, whatIsInForce()));

        band(MenuLayout.WHO, 6, may(ModerationPermission.REPORTS),
                Icons.of(Material.PAPER, "<yellow>Reported",
                        "<gray>" + services().reportService().about(subject).size()
                                + " report(s) about them."),
                "For whoever may read reports",
                click -> new ReportsMenu(services(), viewer, this).open());

        // ── what may be done, one door per kind ───────────────────────────────────────────────
        // Grouped rather than flat. The flat version put nine verbs on one page — ban, tempban,
        // unban, mute, tempmute, unmute, kick, warn, freeze — and somebody looking for "unban" had
        // to read all nine. Each door asks one question, and behind it are only the answers that
        // belong together, handing out and lifting side by side.
        category(1, PunishmentKind.WARNING, ModerationPermission.WARN);
        category(2, PunishmentKind.KICK, ModerationPermission.KICK);
        category(3, PunishmentKind.MUTE, ModerationPermission.MUTE);
        category(4, PunishmentKind.FREEZE, ModerationPermission.FREEZE);
        category(5, PunishmentKind.BAN, ModerationPermission.BAN);

        // ── the practical ─────────────────────────────────────────────────────────────────────
        band(MenuLayout.LAND, 2, may(ModerationPermission.INVSEE),
                Icons.of(Material.CHEST, "<yellow>Look in their inventory",
                        may(ModerationPermission.INVSEE_EDIT)
                                ? "<gray>You may change what is in it."
                                : "<gray>Looking only."),
                "For whoever may look in inventories",
                click -> lookInside());

        band(MenuLayout.LAND, 4, may(ModerationPermission.HISTORY),
                Icons.of(Material.BOOK, "<yellow>Their whole record",
                        "<gray>" + services().punishmentService().history(subject).size()
                                + " entries.",
                        "<dark_gray>Every kind, newest first."),
                "For whoever may read a record",
                click -> new HistoryMenu(services(), viewer, this, subject, subjectName).open());

        // Staff rank. Drawn for everybody, greyed for anybody who is not the server owner — so a
        // moderator can see that ranks exist and that handing them out is not theirs.
        band(MenuLayout.LAND, 7, viewer.isOp()
                        || viewer.hasPermission(de.raindancer.modules.moderation.command
                        .PromoteCommand.USE),
                Icons.of(Material.IRON_HELMET, "<yellow>Staff rank",
                        "<gray>" + services().roster().rankOf(subject)
                                .map(rank -> rank.title()).orElse("Not staff") + ".",
                        "<dark_gray>Promote, demote, or change one permission."),
                "Only the server owner may hand out ranks",
                click -> new RankMenu(services(), viewer, this, subject, subjectName).open());

        band(MenuLayout.LAND, 6, may(ModerationPermission.WARN),
                Icons.of(Material.GOLDEN_APPLE, "<yellow>Put them right",
                        "<gray>Heals and feeds them.",
                        "<dark_gray>For after a fall nobody meant."),
                "For whoever may act on a player",
                click -> putThemRight());
    }

    /**
     * One door per kind of punishment.
     *
     * <p>The lore carries what is in force for that kind, so the page answers "is this person already
     * muted?" without anybody opening the mute door to find out.
     */
    private void category(int column, PunishmentKind kind, ModerationPermission permission) {
        List<String> lore = new ArrayList<>();
        services().punishments().active(subject, kind).ifPresentOrElse(
                one -> {
                    lore.add("<red>Currently " + kind.past() + ".");
                    lore.add("<gray>" + one.reason());
                    lore.add("<gray>" + remaining(one));
                },
                () -> lore.add("<gray>Not " + kind.past() + "."));
        lore.add("");
        lore.add(kind.isLasting()
                ? "<dark_gray>Hand one out, or lift the one in force."
                : "<dark_gray>Hand one out.");

        band(MenuLayout.RULES, column, may(permission),
                Icons.of(CategoryMenu.icon(kind), "<yellow>" + CategoryMenu.title(kind), lore),
                "You may not " + CategoryMenu.verb(kind).toLowerCase(java.util.Locale.ROOT),
                click -> new CategoryMenu(services(), viewer, this, subject, subjectName, kind).open());
    }

    private void putThemRight() {
        if (refusedFor(ModerationPermission.WARN)) {
            return;
        }
        // Core's PlayerAdmin, which answers an Outcome per call rather than throwing when they left
        // between the render and the click.
        services().players().heal(subject);
        services().players().feed(subject);
        services().players().extinguish(subject);
        tell("moderation.put-right", "player", subjectName);
    }

    /**
     * Opens Core's inventory window.
     *
     * <p>Editing armour needs the wider grant, because taking somebody's helmet off by clicking one
     * slot too far is the mistake {@code Access} was split up to prevent. The answer comes back through
     * a callback and always on this thread — reading a logged-out player means reading a file, which is
     * not something to do on the thread also running the world.
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

    /**
     * Checks again at the moment of the click, and says so if the answer changed.
     *
     * <p>The render happened at least one click ago, and a permission can be taken away in between —
     * which is the whole reason a screen may not treat its own greyed-out state as the check.
     */
    private boolean refusedFor(ModerationPermission what) {
        var verdict = canAct(subject, what);
        if (verdict.isRefused()) {
            tellRefusal(verdict);
            return true;
        }
        return false;
    }

    /**
     * The header's lore: what is in force right now, and what they are carrying otherwise.
     *
     * <p>Everything a moderator would otherwise have to open three pages to find out. A line per state
     * that applies, and one saying so when none does — an empty lore reads as a page that failed to
     * load rather than as a player with nothing against them.
     */
    private List<String> whatIsInForce() {
        List<String> lore = new ArrayList<>();
        for (PunishmentKind kind : PunishmentKind.values()) {
            if (!kind.isLasting()) {
                continue;   // a kick and a warning are over the moment they land
            }
            services().punishments().active(subject, kind).ifPresent(one ->
                    lore.add("<red>" + kind.past() + " <gray>— " + one.reason()
                            + " <dark_gray>(" + remaining(one) + ")"));
        }
        if (lore.isEmpty()) {
            lore.add("<green>Nothing in force.");
        }
        lore.add("");
        lore.add("<dark_gray>" + services().punishmentService().history(subject).size()
                + " entr(ies) on record");
        if (services().vanish().isVanished(subject)) {
            lore.add("<dark_gray>Vanished");
        }
        return lore;
    }

    private static String remaining(Punishment one) {
        return one.remainingAt(Instant.now())
                .map(left -> Times.brief(left) + " left")
                .orElse(one.isPermanent() ? "for ever" : "over");
    }

    @Override
    public String describe() {
        return "one player: what is in force, what is on the record, and what may be done";
    }
}
