package de.raindancer.modules.moderation.screen;

import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.model.Sentence;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * How long.
 *
 * <h2>Why the suggestion is a button rather than the only option</h2>
 * The ladder is the server's policy, and policy is what makes punishments consistent — but a moderator
 * who has just read the chat log knows something the ladder does not. So the suggested length is the
 * first button, marked as the suggestion, and the others are there beside it. A screen that only offered
 * the ladder would be one people worked around with the command, which is the same thing with the
 * record lost.
 */
public final class DurationMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** The lengths somebody actually chooses, in the order they read. */
    private static final List<Duration> COMMON = List.of(
            Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(12),
            Duration.ofDays(1), Duration.ofDays(7), Duration.ofDays(30));

    private final UUID subject;
    private final String subjectName;
    private final Reason reason;

    public DurationMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                        String subjectName, Reason reason) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName;
        this.reason = reason;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>How long — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return "How long";
    }

    @Override
    protected void render() {
        Sentence suggested = services().punishmentService().suggest(reason, subject);

        band(MenuLayout.WHO, 4, Icons.of(reason.severity().icon(),
                "<yellow>" + reason.label(),
                "<" + reason.severity().colour() + ">" + reason.severity().describe(),
                "<gray>For <white>" + subjectName + "</white>."));

        band(MenuLayout.RULES, 4, Icons.of(Material.CLOCK,
                        "<green>" + suggested.describe(),
                        "<gray>What this server's ladder says.",
                        "<dark_gray>Their " + (services().punishmentService()
                                .priorOffences(reason, subject) + 1) + " of this."),
                click -> confirm(suggested));

        // Greyed rather than absent, with the ceiling as the reason — so a mod can see that longer
        // bans exist, that they are an admin's to give, and exactly where their own line is.
        int column = 1;
        for (Duration length : COMMON) {
            Sentence sentence = Sentence.of(length);
            var allowed = allowedFor(sentence);
            band(MenuLayout.LAND, column++, allowed.isAllowed(),
                    Icons.of(Material.CLOCK, "<yellow>" + sentence.describe(),
                            "<gray>Instead of the suggestion."),
                    ceilingReason(), click -> confirm(sentence));
        }
        var forEver = allowedFor(Sentence.forEver());
        band(MenuLayout.LAND, column, forEver.isAllowed(),
                Icons.of(Material.BEDROCK, "<red>For ever",
                        "<gray>Until somebody lifts it."),
                ceilingReason(), click -> confirm(Sentence.forEver()));

        toolbar(4, Icons.of(Material.WRITABLE_BOOK, "<yellow>Type a length",
                        "<gray>Anything Rain's Core understands: <white>90m</white>, "
                                + "<white>3d</white>, <white>2M</white>.",
                        "<dark_gray>m is minutes, M is months."),
                click -> askForOne());
    }

    /**
     * Whether this viewer may hand out a ban of this length.
     *
     * <p>Only bans are capped; a mute or a freeze is nobody's business but the permission's, so those
     * come back allowed and the buttons are drawn live.
     */
    private de.raindancer.core.platform.rule.Verdict allowedFor(Sentence sentence) {
        if (!de.raindancer.modules.moderation.rules.BanLimitRule.appliesTo(reason.kind())) {
            return de.raindancer.core.platform.rule.Verdict.allowed();
        }
        return services().banLimitRule().mayBanFor(viewer.getUniqueId(), sentence);
    }

    /** What a greyed length says: the ceiling, in the words somebody would use. */
    private String ceilingReason() {
        return services().banLimitRule().longestFor(viewer.getUniqueId())
                .map(most -> most.isZero()
                        ? "You may not ban"
                        : "You may ban for up to " + services().banLimitRule().capDescribed())
                .orElse("Not allowed");
    }

    /** The chat prompt, which is Core's — one question, one answer, and a timeout. */
    private void askForOne() {
        viewer.closeInventory();
        tell("moderation.type-a-length");
        services().prompts().ask(viewer.getUniqueId(), "moderation", Duration.ofSeconds(60),
                typed -> Sentence.parse(typed).ifPresentOrElse(
                        // Checked again, because a typed length bypasses every greyed button above.
                        wanted -> {
                            var allowed = allowedFor(wanted);
                            if (allowed.isRefused()) {
                                allowed.refusal().ifPresent(key -> tell(key, "detail",
                                        allowed.detail() == null ? "" : allowed.detail()));
                                return;
                            }
                            punish(wanted);
                        },
                        // Empty is a typo, never "for ever" — see Sentence. Saying so beats handing
                        // out a permanent ban because somebody wrote "2 hours" with a space.
                        () -> tell("moderation.unreadable-length", "text", typed)),
                () -> tell("moderation.nothing-typed"));
    }

    private void confirm(Sentence sentence) {
        List<String> what = new ArrayList<>();
        what.add("<gray>" + subjectName + " would be " + reason.kind().past()
                + " <white>" + sentence.describe() + "</white>.");
        what.add("<gray>Reason: <white>" + reason.label() + "</white>.");

        new ConfirmScreen(services(), viewer, this,
                "<yellow>" + reason.label() + " — " + sentence.describe() + "?",
                what, () -> punish(sentence)).open();
    }

    private void punish(Sentence sentence) {
        var verdict = canAct(subject, permissionFor());
        if (verdict.isRefused()) {
            tellRefusal(verdict);
            return;
        }
        // The last gate. Every path here has already asked, which is the point: a limit checked in one
        // place is a limit that holds only on that path.
        var withinTheirLimit = allowedFor(sentence);
        if (withinTheirLimit.isRefused()) {
            withinTheirLimit.refusal().ifPresent(key -> tell(key, "detail",
                    withinTheirLimit.detail() == null ? "" : withinTheirLimit.detail()));
            return;
        }
        services().punishmentService().punish(viewer.getUniqueId(), viewer.getName(), subject,
                subjectName, reason.kind(), sentence, reason.label());
        tell("moderation.punished", "player", subjectName, "what", reason.kind().past(),
                "length", sentence.describe());
        viewer.closeInventory();
    }

    private ModerationPermission permissionFor() {
        return switch (reason.kind()) {
            case BAN -> ModerationPermission.TEMPBAN;   // see CategoryMenu#permission
            case MUTE -> ModerationPermission.MUTE;
            case KICK -> ModerationPermission.KICK;
            case WARNING -> ModerationPermission.WARN;
            case FREEZE -> ModerationPermission.FREEZE;
        };
    }

    @Override
    public String describe() {
        return "how long a punishment is for, with the ladder's suggestion first";
    }
}
