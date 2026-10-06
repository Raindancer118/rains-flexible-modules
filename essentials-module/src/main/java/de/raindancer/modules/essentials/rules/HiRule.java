package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;

import java.util.List;
import java.util.UUID;

/** Whether a click on "Say Hi!" greets anybody, and what the greeting says. */
public final class HiRule extends AbstractRule<HiRule.Click> {

    /** One click: who clicked, whose join line it was under, and whether they already greeted this join. */
    public record Click(UUID clicker, UUID joiner, boolean alreadyGreeted) {
    }

    public HiRule() {
        super("somebody else may say hi once to whoever just joined");
    }

    @Override
    public Verdict judge(Click click) {
        if (click.clicker().equals(click.joiner())) {
            return Verdict.refused("essentials.welcome.hi-yourself");
        }
        if (click.alreadyGreeted()) {
            return Verdict.refused("essentials.welcome.hi-already");
        }
        return Verdict.allowed();
    }

    /**
     * A greeting from the list, then the name: "Hey Bo". {@code pick} may be any number — it is folded
     * into the list — so a random int is enough. Blank lines are skipped; an empty list says "Hi".
     */
    public static String line(List<String> greetings, String name, int pick) {
        List<String> usable = greetings.stream().filter(line -> line != null && !line.isBlank())
                .map(String::strip).toList();
        String greeting = usable.isEmpty() ? "Hi" : usable.get(Math.floorMod(pick, usable.size()));
        return greeting + " " + name;
    }
}
