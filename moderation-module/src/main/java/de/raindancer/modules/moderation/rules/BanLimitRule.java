package de.raindancer.modules.moderation.rules;

import de.raindancer.core.moderation.punishment.Durations;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Sentence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * How long a ban this person may hand out.
 *
 * <h2>Why a cap rather than one more permission</h2>
 * Because "may they ban?" is the wrong question. A mod dealing with a griefer at two in the morning has
 * to be able to stop them <em>now</em> — waiting for an admin to wake up is how a server loses a
 * weekend's building. What a mod should not be able to do is end somebody's time on the server
 * permanently on their own judgement, with nobody else having looked.
 *
 * <p>So: two nodes and a length between them. {@link ModerationPermission#TEMPBAN} bans up to the cap,
 * {@link ModerationPermission#BAN} bans for as long as it likes. The cap is a setting, because a day is
 * this server's answer and not a law.
 *
 * <h2>Why it is a rule and not a check inside the command</h2>
 * The screens have to ask it too, <em>before</em> anything is pressed — the duration menu greys what a
 * mod may not choose and says why, rather than accepting a week and refusing afterwards. A limit
 * enforced only at the click is a limit the interface lies about, and the version of this that lived in
 * the command was exactly that.
 */
public final class BanLimitRule implements IModerationRule {

    /** Refusal keys, which are message keys. */
    public static final String TOO_LONG = "moderation.ban.too-long-for-you";
    public static final String NOT_FOR_EVER = "moderation.ban.not-for-ever-for-you";
    public static final String NOT_YOURS_TO_LIFT = "moderation.ban.not-yours-to-lift";

    private final StaffRule staff;
    private final Duration cap;

    /**
     * @param cap the longest a {@link ModerationPermission#TEMPBAN} holder may ban for; null or
     *            non-positive is read as "no temporary bans at all", which is a coherent thing for a
     *            server to want and a silly thing to crash over
     */
    public BanLimitRule(StaffRule staff, Duration cap) {
        this.staff = staff;
        this.cap = cap == null || cap.isNegative() ? Duration.ZERO : cap;
    }

    /** Whether this kind of punishment is capped at all. Only a ban is. */
    public static boolean appliesTo(PunishmentKind kind) {
        return kind == PunishmentKind.BAN;
    }

    /** Whether they may hand out a ban of any length whatsoever. */
    public boolean mayBanAtAll(UUID actor) {
        return staff.may(actor, ModerationPermission.BAN)
                || staff.may(actor, ModerationPermission.TEMPBAN);
    }

    /**
     * Whether they may hand out a ban of this length.
     *
     * <p>The permission is checked first, so somebody with neither node learns nothing about the cap.
     */
    public Verdict mayBanFor(UUID actor, Sentence sentence) {
        if (staff.may(actor, ModerationPermission.BAN)) {
            return Verdict.allowed();       // no ceiling, permanent included
        }
        if (!staff.may(actor, ModerationPermission.TEMPBAN)) {
            return Verdict.refused(StaffRule.NO_PERMISSION);
        }
        Sentence howLong = sentence == null ? Sentence.forEver() : sentence;
        if (howLong.isPermanent()) {
            return Verdict.refused(NOT_FOR_EVER);
        }
        Duration wanted = howLong.length().orElse(Duration.ZERO);
        if (wanted.compareTo(cap) > 0) {
            return Verdict.refused(TOO_LONG, Durations.describe(cap));
        }
        return Verdict.allowed();
    }

    /**
     * The longest they may ban for.
     *
     * <p>Empty means no ceiling at all; {@link Duration#ZERO} means they may not ban. Both are worth
     * distinguishing, because a screen shows a ceiling and hides nothing.
     */
    public Optional<Duration> longestFor(UUID actor) {
        if (staff.may(actor, ModerationPermission.BAN)) {
            return Optional.empty();
        }
        if (!staff.may(actor, ModerationPermission.TEMPBAN)) {
            return Optional.of(Duration.ZERO);
        }
        return Optional.of(cap);
    }

    /**
     * The same sentence, brought within what this person may hand out.
     *
     * <p>What the duration menu uses. A mod picking "Griefing", whose ladder starts at three days, gets
     * a day and a line saying an admin can go further — which is more useful than a button that refuses
     * them and does not say what would work.
     */
    public Sentence clamp(UUID actor, Sentence sentence) {
        Optional<Duration> ceiling = longestFor(actor);
        if (ceiling.isEmpty()) {
            return sentence;        // no limit
        }
        Duration most = ceiling.get();
        if (most.isZero()) {
            return sentence;        // they may not ban at all; the refusal says so, not a clamp
        }
        if (sentence == null || sentence.isPermanent()) {
            return Sentence.of(most);
        }
        return sentence.length().orElse(Duration.ZERO).compareTo(most) > 0
                ? Sentence.of(most)
                : sentence;
    }

    /**
     * Whether they may lift a ban.
     *
     * <p>A mod may undo a temporary one — otherwise they can hand out a day and then not take it back
     * when the griefer turns out to have been the one being griefed. A permanent ban is an admin's
     * decision, and undoing one is that same decision reversed.
     */
    public Verdict mayLift(UUID actor, boolean thePunishmentIsPermanent) {
        if (staff.may(actor, ModerationPermission.BAN)) {
            return Verdict.allowed();
        }
        if (!staff.may(actor, ModerationPermission.TEMPBAN)) {
            return Verdict.refused(StaffRule.NO_PERMISSION);
        }
        return thePunishmentIsPermanent
                ? Verdict.refused(NOT_YOURS_TO_LIFT)
                : Verdict.allowed();
    }

    /** How long the cap is, in words — for the lore on a greyed button. */
    public String capDescribed() {
        return Durations.describe(cap);
    }

    @Override
    public String describe() {
        return "how long a ban somebody may hand out: any length with 'ban', up to "
                + capDescribed() + " with 'tempban'";
    }
}
