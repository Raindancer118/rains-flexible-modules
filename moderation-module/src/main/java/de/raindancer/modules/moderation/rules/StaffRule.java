package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.moderation.model.ModerationPermission;

import java.util.UUID;

/**
 * Who may do what, to whom.
 *
 * <h2>Why this takes ids rather than a {@code CommandSender}</h2>
 * So it can be asked without a server. Every question here is one a <em>screen</em> asks to decide
 * whether to grey a button, several times per render — and a rule that needed a running Paper to answer
 * would be a rule nobody tested and every screen worked around with an if.
 *
 * <p>The permission lookup arrives as {@link Rights}: one method, backed by {@code hasPermission} at
 * runtime and by a map in a test.
 *
 * <h2>The console</h2>
 * A null actor is the console, and the console may do everything — including acting on a protected
 * account. Protection is what stops one moderator banning another in a fit of pique; it must not be
 * what stops the owner dealing with a compromised staff account, which is the case it would fail in
 * exactly when it mattered. It is also why the console is the only place protection can be handed out
 * at all: see {@code store.ImmuneStaff}.
 */
public final class StaffRule implements IModerationRule {

    /** Refusal keys, which are message keys. Constants so a screen and a command word it the same. */
    public static final String NO_PERMISSION = "moderation.no-permission";
    public static final String NOT_YOURSELF = "moderation.not-yourself";
    public static final String THEY_ARE_IMMUNE = "moderation.they-are-immune";
    public static final String NOBODY_THERE = "moderation.nobody-there";

    /** Whether somebody holds a permission node. The one thing this rule cannot work out for itself. */
    @FunctionalInterface
    public interface Rights {

        /** @param who null for the console, which holds everything */
        boolean has(UUID who, String node);
    }

    /**
     * Whether an account may not be acted on.
     *
     * <p>Its own seam rather than another {@link Rights} lookup, and that is the whole correction: a
     * permission is a fact about somebody who is <em>online</em>, and the subject of a ban usually is
     * not. What is behind this at runtime is the console-written list plus "is an operator", neither of
     * which needs the person to be here to answer.
     */
    @FunctionalInterface
    public interface Protection {

        boolean covers(UUID subject);
    }

    private final Rights rights;
    private final Protection protection;

    public StaffRule(Rights rights, Protection protection) {
        this.rights = rights == null ? (who, node) -> false : rights;
        // Nobody protected is the safe default for a *test* and the wrong one for a server, which is
        // why the module wires it explicitly and there is no constructor that leaves it out.
        this.protection = protection == null ? subject -> false : protection;
    }

    /** Whether they may do this at all, ignoring who it would be done to. */
    public boolean may(UUID actor, ModerationPermission what) {
        if (actor == null) {
            return true;    // the console
        }
        return what != null && rights.has(actor, what.node());
    }

    /** Whether a moderator is barred from acting on this person. */
    public boolean isImmune(UUID subject) {
        return subject != null && protection.covers(subject);
    }

    /**
     * Whether this actor may do this to this person.
     *
     * <p>The permission is checked <em>before</em> the subject, deliberately: otherwise somebody with
     * no moderation permissions at all could learn who is immune by watching which refusal they get
     * back.
     */
    public Verdict canAct(UUID actor, UUID subject, ModerationPermission what) {
        if (!may(actor, what)) {
            return Verdict.refused(NO_PERMISSION);
        }
        if (subject == null) {
            return Verdict.refused(NOBODY_THERE);
        }
        if (actor == null) {
            return Verdict.allowed();   // the console, past every guard below
        }
        if (actor.equals(subject)) {
            // Yourself. Answered here and returned here, so the immunity check below never sees it:
            // protection is what stops one moderator acting on another, and an owner is protected, so
            // asking it about yourself made every owner unable to heal, feed or unfreeze themselves —
            // refused with "that account is protected", about their own account.
            //
            // The ban half stays. A moderator who bans themselves cannot come back and lift it, which
            // has happened on a real server and needed a database edit to undo.
            return what.aimableAtSelf() ? Verdict.allowed() : Verdict.refused(NOT_YOURSELF);
        }
        if (isImmune(subject)) {
            return Verdict.refused(THEY_ARE_IMMUNE);
        }
        return Verdict.allowed();
    }

    @Override
    public String describe() {
        return "who may act on whom: the permission, then yourself, then immunity";
    }
}
