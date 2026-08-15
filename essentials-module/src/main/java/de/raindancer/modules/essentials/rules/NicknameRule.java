package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.essentials.model.Nickname;

/**
 * Whether a nickname may be worn.
 *
 * <h2>Why "is somebody already called this" is a rule and not a database check</h2>
 * Because the interesting decision — blank, too long, blocklisted, or the same name as somebody real
 * — is exactly as testable as any other rule once the actual lookups have already happened.
 * {@link Request} carries the answers, worked out by whoever calls this against the server's own
 * player list and the configured blocklists; the rule itself reaches for nothing.
 *
 * <h2>Why a real player's name is refused and not merely discouraged</h2>
 * A nickname identical to somebody else's actual name is not a style choice, it is impersonation —
 * the one thing every other player has to trust does not happen for {@code /msg}, for a ban
 * appeal, for a report naming the wrong person. Refusing it outright is the only answer that keeps
 * a name meaning one person.
 *
 * <h2>Why blocklisted comes before "too long"</h2>
 * A name can be both — long enough to be over the limit and on the blocklist besides — and which
 * refusal a player is shown matters less than which one the caller acts on: only {@link #BLOCKED}
 * tells the service to report (and possibly ban), so a name that would also have failed on length
 * must still be caught by the check that has consequences.
 */
public final class NicknameRule extends AbstractRule<NicknameRule.Request> {

    public static final String BLANK = "essentials.nick.blank";
    public static final String TOO_LONG = "essentials.nick.too-long";
    public static final String NAME_TAKEN = "essentials.nick.taken";
    public static final String BLOCKED = "essentials.nick.blocked";

    public NicknameRule() {
        super("a nickname is not blank, not too long, not blocklisted, and not somebody else's real "
                + "name");
    }

    /** Whether — and how severely — a nickname matched one of the configured blocklists. */
    public enum BlockMatch {
        NONE, REPORTED, BANNED
    }

    /**
     * @param nickname  what was asked for
     * @param maxLength the server's own limit, on the plain text — colour costs nothing towards it
     * @param nameInUse whether a real player already answers to this, worked out by the caller
     * @param blocked   whether this matches a configured blocklist, and which one — worked out by
     *                  the caller, which already has the lists to check against
     */
    public record Request(Nickname nickname, int maxLength, boolean nameInUse, BlockMatch blocked) {

        public Request {
            blocked = blocked == null ? BlockMatch.NONE : blocked;
        }

        /** The common case: nothing on any blocklist. */
        public static Request of(Nickname nickname, int maxLength, boolean nameInUse) {
            return new Request(nickname, maxLength, nameInUse, BlockMatch.NONE);
        }
    }

    @Override
    public Verdict judge(Request request) {
        if (request.nickname().isBlank()) {
            return Verdict.refused(BLANK);
        }
        if (request.blocked() != BlockMatch.NONE) {
            return Verdict.refused(BLOCKED);
        }
        if (request.nickname().length() > request.maxLength()) {
            return Verdict.refused(TOO_LONG, request.maxLength());
        }
        if (request.nameInUse()) {
            return Verdict.refused(NAME_TAKEN, request.nickname().plain());
        }
        return Verdict.allowed();
    }
}
