package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;

import java.util.List;
import java.util.UUID;

/**
 * Whether a click on a reaction button — Say Hi! under a join, Congrats! under an advancement — says
 * anything, and what. Somebody else, once per line. Each kind answers a refusal in its own words.
 */
public final class ReactionRule extends AbstractRule<ReactionRule.Click> {

    /** One click: who clicked, whose line it was under, and whether they already reacted to it. */
    public record Click(UUID clicker, UUID subject, boolean alreadyReacted) {
    }

    private final String yourselfKey;
    private final String alreadyKey;

    public ReactionRule(String yourselfKey, String alreadyKey) {
        super("somebody else may react once to a line about somebody");
        this.yourselfKey = yourselfKey;
        this.alreadyKey = alreadyKey;
    }

    @Override
    public Verdict judge(Click click) {
        if (click.clicker().equals(click.subject())) {
            return Verdict.refused(yourselfKey);
        }
        if (click.alreadyReacted()) {
            return Verdict.refused(alreadyKey);
        }
        return Verdict.allowed();
    }

    /**
     * A phrase from the list, then the name: "Hey Bo", "GG Bo". {@code pick} may be any number — it is
     * folded into the list. Blank lines are skipped; an empty list uses {@code fallback}.
     */
    public static String line(List<String> phrases, String name, int pick, String fallback) {
        List<String> usable = phrases.stream().filter(phrase -> phrase != null && !phrase.isBlank())
                .map(String::strip).toList();
        String phrase = usable.isEmpty() ? fallback : usable.get(Math.floorMod(pick, usable.size()));
        return phrase + " " + name;
    }
}
