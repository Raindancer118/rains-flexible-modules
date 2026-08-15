package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.essentials.model.Nickname;

/**
 * Whether a nickname may be worn.
 *
 * <h2>Why "is somebody already called this" is a rule and not a database check</h2>
 * Because the interesting decision — blank, too long, or the same name as somebody real — is exactly
 * as testable as any other rule once the actual lookup has already happened. {@link Request} carries
 * the answer to "does a real player already go by this name" as a plain boolean, worked out by
 * whoever calls this against the server's own player list; the rule itself reaches for nothing.
 *
 * <h2>Why a real player's name is refused and not merely discouraged</h2>
 * A nickname identical to somebody else's actual name is not a style choice, it is impersonation —
 * the one thing every other player has to trust does not happen for {@code /msg}, for a ban
 * appeal, for a report naming the wrong person. Refusing it outright is the only answer that keeps
 * a name meaning one person.
 */
public final class NicknameRule extends AbstractRule<NicknameRule.Request> {

    public static final String BLANK = "essentials.nick.blank";
    public static final String TOO_LONG = "essentials.nick.too-long";
    public static final String NAME_TAKEN = "essentials.nick.taken";

    public NicknameRule() {
        super("a nickname is not blank, not too long, and not somebody else's real name");
    }

    /**
     * @param nickname   what was asked for
     * @param maxLength  the server's own limit, on the plain text — colour costs nothing towards it
     * @param nameInUse  whether a real player already answers to this, worked out by the caller
     */
    public record Request(Nickname nickname, int maxLength, boolean nameInUse) {
    }

    @Override
    public Verdict judge(Request request) {
        if (request.nickname().isBlank()) {
            return Verdict.refused(BLANK);
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
