package de.raindancer.modules.moderation.screen;

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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * One kind of punishment, and everything that can be done with it.
 *
 * <h2>Why the hub is categories rather than verbs</h2>
 * The flat version put nine buttons on one page — ban, tempban, unban, mute, tempmute, unmute, kick,
 * warn, freeze — and a moderator looking for "unban" had to read all nine to find it. Grouped, the top
 * page asks one question ("what kind of thing are you doing?") and this one asks the second, with only
 * the three or four answers that belong together. The band a player is used to seeing does not change
 * shape, and every verb is exactly two clicks from their name.
 *
 * <p>Handing out and lifting live on the same page deliberately. They are the two halves of one
 * decision, and the version where lifting lived somewhere else is the version where a moderator banned
 * somebody, realised it was the wrong person, and had to go back two pages to undo it.
 */
public final class CategoryMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;
    private final PunishmentKind kind;

    public CategoryMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                        String subjectName, PunishmentKind kind) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
        this.kind = kind;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + title(kind) + " — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return title(kind);
    }

    @Override
    protected void render() {
        Optional<Punishment> inForce = services().punishments().active(subject, kind);

        // Who this is about, and what is already in force — so nobody hands out a second mute to
        // somebody who is already muted without noticing.
        band(MenuLayout.WHO, 4, Icons.head(subject, "<white>" + subjectName, state(inForce)));

        // ── handing one out ───────────────────────────────────────────────────────────────────
        band(MenuLayout.RULES, 3, may(permission()),
                Icons.of(icon(kind), "<yellow>" + verb(kind),
                        "<gray>Pick a reason from the list.",
                        kind.isLasting()
                                ? "<dark_gray>Then how long, with the ladder's suggestion first."
                                : "<dark_gray>Asks before it happens."),
                "You may not " + verb(kind).toLowerCase(Locale.ROOT),
                click -> new PunishMenu(services(), viewer, this, subject, subjectName, kind).open());

        // ── ending it ─────────────────────────────────────────────────────────────────────────
        if (kind.isLasting()) {
            boolean canLift = may(permission()) && inForce.isPresent();
            List<String> lore = new ArrayList<>();
            inForce.ifPresentOrElse(
                    one -> {
                        lore.add("<gray>" + one.reason());
                        lore.add("<gray>" + remaining(one));
                        lore.add("<dark_gray>It stays on the record either way.");
                    },
                    () -> lore.add("<gray>Nothing in force."));

            band(MenuLayout.RULES, 5, canLift,
                    Icons.of(Material.LIME_DYE, "<green>" + lift(kind), lore),
                    inForce.isEmpty() ? "Nothing to lift" : "You may not lift this",
                    click -> new ConfirmScreen(services(), viewer, this,
                            "<green>" + lift(kind) + " — " + subjectName + "?",
                            List.of("<gray>" + afterLifting(kind),
                                    "<dark_gray>The punishment stays on their record."),
                            this::liftIt).open());
        }

        // ── the record, filtered to this kind ─────────────────────────────────────────────────
        band(MenuLayout.LAND, 4, may(ModerationPermission.HISTORY),
                Icons.of(Material.BOOK, "<yellow>Every " + verb(kind).toLowerCase(Locale.ROOT)
                                + " they have had",
                        "<gray>" + countOnRecord() + " on record.",
                        "<dark_gray>Opens the whole record."),
                "For whoever may read a record",
                click -> new HistoryMenu(services(), viewer, this, subject, subjectName).open());

        // ── the one irreversible thing, for the categories that have one ──────────────────────
        if (kind == PunishmentKind.BAN && may(ModerationPermission.BAN)) {
            danger(Icons.of(Material.BEDROCK, "<red>Ban for ever",
                            "<gray>Straight to permanent, without a reason from the list.",
                            "<dark_gray>Asks first."),
                    click -> new ConfirmScreen(services(), viewer, this,
                            "<red>Ban " + subjectName + " for ever?",
                            List.of("<gray>They are thrown off now and cannot come back.",
                                    "<gray>Any moderator can lift it again."),
                            this::banForEver).open());
        }
    }

    private void liftIt() {
        var verdict = canAct(subject, permission());
        if (verdict.isRefused()) {
            tellRefusal(verdict);
            return;
        }
        services().punishmentService().lift(viewer.getUniqueId(), viewer.getName(), subject,
                subjectName, kind, "lifted from the moderation screen");
        tell("moderation.lifted", "player", subjectName, "what", kind.past());
        open();
    }

    private void banForEver() {
        var verdict = canAct(subject, ModerationPermission.BAN);
        if (verdict.isRefused()) {
            tellRefusal(verdict);
            return;
        }
        services().punishmentService().punish(viewer.getUniqueId(), viewer.getName(), subject,
                subjectName, PunishmentKind.BAN, Sentence.forEver(), "banned by a moderator");
        tell("moderation.punished", "player", subjectName, "what", PunishmentKind.BAN.past(),
                "length", "for ever");
        viewer.closeInventory();
    }

    /** How many of this kind are on their record, for the lore line on the history button. */
    private long countOnRecord() {
        return services().punishmentService().history(subject).stream()
                .filter(past -> past.kind() == kind)
                .count();
    }

    private List<String> state(Optional<Punishment> inForce) {
        List<String> lore = new ArrayList<>();
        inForce.ifPresentOrElse(
                one -> {
                    lore.add("<red>Currently " + kind.past() + ".");
                    lore.add("<gray>" + one.reason());
                    lore.add("<gray>" + remaining(one));
                },
                () -> lore.add("<green>Not " + kind.past() + "."));
        return lore;
    }

    private static String remaining(Punishment one) {
        return one.remainingAt(Instant.now())
                .map(left -> Times.brief(left) + " left")
                .orElse(one.isPermanent() ? "for ever" : "over");
    }

    // ── the words, in one place so the page and its confirmations agree ──────────────────────

    private ModerationPermission permission() {
        return switch (kind) {
            case BAN -> ModerationPermission.BAN;
            case MUTE -> ModerationPermission.MUTE;
            case KICK -> ModerationPermission.KICK;
            case WARNING -> ModerationPermission.WARN;
            case FREEZE -> ModerationPermission.FREEZE;
        };
    }

    static String title(PunishmentKind kind) {
        return switch (kind) {
            case BAN -> "Bans";
            case MUTE -> "Mutes";
            case KICK -> "Kicks";
            case WARNING -> "Warnings";
            case FREEZE -> "Freezes";
        };
    }

    static String verb(PunishmentKind kind) {
        return switch (kind) {
            case BAN -> "Ban";
            case MUTE -> "Mute";
            case KICK -> "Kick";
            case WARNING -> "Warn";
            case FREEZE -> "Freeze";
        };
    }

    private static String lift(PunishmentKind kind) {
        return switch (kind) {
            case BAN -> "Lift the ban";
            case MUTE -> "Lift the mute";
            case FREEZE -> "Unfreeze them";
            default -> "Lift it";
        };
    }

    private static String afterLifting(PunishmentKind kind) {
        return switch (kind) {
            case BAN -> "They may come back.";
            case MUTE -> "They may talk again.";
            case FREEZE -> "They may build again.";
            default -> "It stops applying.";
        };
    }

    static Material icon(PunishmentKind kind) {
        return switch (kind) {
            case BAN -> Material.BARRIER;
            case MUTE -> Material.PAPER;
            case KICK -> Material.LEATHER_BOOTS;
            case WARNING -> Material.YELLOW_BANNER;
            case FREEZE -> Material.PACKED_ICE;
        };
    }

    @Override
    public String describe() {
        return "one kind of punishment: handing it out, ending it, and what is on the record";
    }
}
