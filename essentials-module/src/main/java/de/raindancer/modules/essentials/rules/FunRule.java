package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;

import java.time.Duration;
import java.util.Optional;

/** Whether {@code /roast} or {@code /joke} may be used right now: switched on, and not too soon after the last. */
public final class FunRule extends AbstractRule<FunRule.Ask> {

    /**
     * @param command   which one, for the refusal to name
     * @param enabled   whether the owner has it switched on
     * @param remaining how long until this player may use it again, if they have to wait
     * @param mayBypass whether they may skip the wait
     */
    public record Ask(String command, boolean enabled, Optional<Duration> remaining, boolean mayBypass) {
    }

    public FunRule() {
        super("a roast or a joke needs the command switched on and the last one far enough back");
    }

    @Override
    public Verdict judge(Ask ask) {
        if (!ask.enabled()) {
            return Verdict.refused("essentials.fun.switched-off", ask.command());
        }
        if (!ask.mayBypass() && ask.remaining().isPresent()) {
            long seconds = Math.max(1, (ask.remaining().get().toMillis() + 999) / 1000);
            return Verdict.refused("essentials.fun.cooling-down", String.valueOf(seconds));
        }
        return Verdict.allowed();
    }
}
