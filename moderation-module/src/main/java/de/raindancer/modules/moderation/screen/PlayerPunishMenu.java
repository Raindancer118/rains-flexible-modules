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
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What to do about a player: one door per kind of punishment, each saying what of that kind is in force,
 * a fine, a broken server rule, and — on the danger slot — banning for good.
 *
 * <pre>
 *  row 0   ·  ·  ·  [status] [head] [standing]  ·  ·  ·
 *  row 1   Escalate   [warn]  ·  [kick]  ·  [ban]
 *  row 2   Restrict        [mute]  ·  [freeze]
 *  row 3   Other           [fine]  ·  [broke a rule]
 *  row 5                  [ban for ever]
 * </pre>
 */
public final class PlayerPunishMenu extends PlayerScreen {

    public PlayerPunishMenu(ModerationServices services, Player viewer, Menu parent, UUID subject, String subjectName) {
        super(services, viewer, parent, subject, subjectName);
    }

    @Override
    protected String room() {
        return "Punish";
    }

    @Override
    protected void render() {
        header();
        group(MenuLayout.WHO, Material.RED_STAINED_GLASS_PANE, "<red>Escalate");
        group(MenuLayout.RULES, Material.ORANGE_STAINED_GLASS_PANE, "<gold>Restrict");
        group(MenuLayout.LAND, Material.YELLOW_STAINED_GLASS_PANE, "<yellow>Other");
        category(MenuLayout.WHO, 2, PunishmentKind.WARNING, ModerationPermission.WARN);
        category(MenuLayout.WHO, 4, PunishmentKind.KICK, ModerationPermission.KICK);
        category(MenuLayout.WHO, 6, PunishmentKind.BAN, ModerationPermission.BAN);
        category(MenuLayout.RULES, 3, PunishmentKind.MUTE, ModerationPermission.MUTE);
        category(MenuLayout.RULES, 5, PunishmentKind.FREEZE, ModerationPermission.FREEZE);
        fineDoor();
        boolean rules = !services().ruleBreaches().rules().isEmpty();
        band(MenuLayout.LAND, 5, may(ModerationPermission.WARN) && rules,
                Icons.of(Material.IRON_BARS, "<yellow>Broke a rule", "<gray>Pick the rule; its next",
                        "<gray>punishment is handed out.", "<dark_gray>The same everybody reads in /rules."),
                rules ? "For whoever may hand out punishments" : "This server has no rules written down",
                click -> new RuleBreachMenu(services(), viewer, this, subject, subjectName).open());
        permanentBan();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Each door says what of its kind is in force.",
                "<gray>Bedrock at the bottom: banned for good, after asking.");
    }

    /**
     * One door per kind of punishment. The lore carries what is in force for that kind, so the page
     * answers "is this person already muted?" without anybody opening the mute door to find out.
     */
    private void category(int band, int column, PunishmentKind kind, ModerationPermission permission) {
        List<String> lore = new ArrayList<>();
        services().punishments().active(subject, kind).ifPresentOrElse(
                one -> {
                    lore.add("<red>Currently " + kind.past() + ".");
                    lore.add("<gray>" + one.reason());
                    lore.add("<gray>" + remaining(one));
                },
                () -> lore.add("<gray>Not " + kind.past() + "."));
        lore.add("");
        lore.add(kind.isLasting() ? "<dark_gray>Hand one out, or lift the one in force." : "<dark_gray>Hand one out.");
        band(band, column, may(permission),
                Icons.of(CategoryMenu.icon(kind), "<yellow>" + CategoryMenu.title(kind), lore),
                "You may not " + CategoryMenu.verb(kind).toLowerCase(java.util.Locale.ROOT),
                click -> new CategoryMenu(services(), viewer, this, subject, subjectName, kind).open());
    }

    /** Fining them: you type the amount and why. Greyed with the reason when there is nothing to charge or no right to. */
    private void fineDoor() {
        boolean economy = services().fines().hasEconomy();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Takes money from their balance, offline or not.");
        services().fines().limitFor(viewer.getUniqueId()).ifPresent(most -> lore.add("<gray>You may fine up to <white>"
                + de.raindancer.core.social.economy.Fees.format(most) + "</white>."));
        lore.add("<dark_gray>You type the amount, then why. Asks first.");
        band(MenuLayout.LAND, 3, may(ModerationPermission.FINE) && economy,
                Icons.of(Material.GOLD_INGOT, "<yellow>Fine", lore),
                economy ? "You may not fine" : "This server has no economy",
                click -> FinesMenu.askForAFine(viewer, this, services(), subject, subjectName));
    }

    /**
     * The one irreversible thing: gone for good. On the danger slot, flanked by navigation, so a misclick
     * costs a page rather than a player, and it only ever opens a confirmation. Greyed for a mod rather
     * than absent, so the page has the same shape for everybody and says whose decision it is.
     */
    private void permanentBan() {
        boolean allowed = may(ModerationPermission.BAN);
        var button = Icons.of(Material.BEDROCK, "<red>Ban for ever",
                "<gray>Gone for good, straight away.",
                "<gray>No reason from the list, no length to pick.",
                "",
                allowed
                        ? "<dark_gray>Asks before it happens."
                        : "<dark_gray>An admin's decision — you can ban for up to "
                        + services().banLimitRule().capDescribed() + ".");
        danger(allowed ? button : Icons.locked(button, "A permanent ban is an admin's decision"),
                click -> {
                    if (!allowed) {
                        tell("moderation.ban.not-for-ever-for-you", "detail", services().banLimitRule().capDescribed());
                        return;
                    }
                    new ConfirmScreen(services(), viewer, this,
                            "<red>Ban " + subjectName + " for ever?",
                            List.of("<gray>They are thrown off now and cannot come back.",
                                    "<gray>It goes on their record, and any admin can lift it.",
                                    "<dark_gray>For anything less than for ever, use Ban above."),
                            this::banForEver).open();
                });
    }

    /** Hands out the permanent ban, having asked twice. */
    private void banForEver() {
        // Asked again at the moment it happens: the page was drawn at least two clicks ago, and this is
        // the one action on it that cannot be undone by pressing the button a second time.
        var verdict = canAct(subject, ModerationPermission.BAN);
        if (verdict.isRefused()) {
            tellRefusal(verdict);
            return;
        }
        var withinTheirLimit = services().banLimitRule().mayBanFor(viewer.getUniqueId(), Sentence.forEver());
        if (withinTheirLimit.isRefused()) {
            withinTheirLimit.refusal().ifPresent(key -> tell(key, "detail",
                    withinTheirLimit.detail() == null ? "" : withinTheirLimit.detail()));
            return;
        }
        services().punishmentService().punish(viewer.getUniqueId(), viewer.getName(), subject,
                subjectName, PunishmentKind.BAN, Sentence.forEver(), "banned permanently");
        tell("moderation.punished", "player", subjectName, "what", PunishmentKind.BAN.past(), "length", "for ever");
        viewer.closeInventory();
    }

    private static String remaining(Punishment one) {
        return one.remainingAt(Instant.now())
                .map(left -> Times.brief(left) + " left")
                .orElse(one.isPermanent() ? "for ever" : "over");
    }

    @Override
    public String describe() {
        return "what to do about a player: warn, kick, mute, freeze, ban, fine, broke a rule, ban for ever";
    }
}
