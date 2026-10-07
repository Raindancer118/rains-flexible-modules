package de.raindancer.modules.playerutils.rules;

import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.model.Verdict;

import java.util.function.Predicate;

/**
 * Who may point which action at whom.
 *
 * <ul>
 *   <li>At yourself: the action's node. At somebody else: that and {@code .others}.</li>
 *   <li>Anything that hurts or takes away is refused against a player holding {@link #EXEMPT} — unless the
 *       asker holds {@link #BYPASS}. Exemption is from harm, not from help: an exempt player can still be
 *       healed.</li>
 *   <li>The console is the owner at the keyboard and may do anything.</li>
 * </ul>
 */
public final class TargetRule implements IPlayerUtilsRule {

    public static final String EXEMPT = "rainsplayerutils.exempt";
    public static final String BYPASS = "rainsplayerutils.exempt.bypass";

    /** Who is asking: the console, or somebody with permissions. */
    public record Asker(boolean console, Predicate<String> has) {

        public static final Asker CONSOLE = new Asker(true, node -> true);

        public boolean holds(String node) {
            return console || has.test(node);
        }
    }

    /**
     * @param self      whether the target is the asker
     * @param targetHas the target's permissions; only asked about {@link #EXEMPT}
     */
    public Verdict judge(Action action, Asker asker, boolean self, Predicate<String> targetHas) {
        if (self && action.self() == Action.Self.NEVER) {
            return Verdict.no("playerutils.not-on-yourself", "action", action.word());
        }
        if (asker.console()) {
            return Verdict.YES;
        }
        if (!asker.holds(action.node())) {
            return Verdict.no("playerutils.no-permission", "action", action.word());
        }
        if (self) {
            return Verdict.YES;
        }
        if (!asker.holds(action.othersNode())) {
            return Verdict.no("playerutils.no-permission-others", "action", action.word());
        }
        if (action.isHarmful() && targetHas.test(EXEMPT) && !asker.holds(BYPASS)) {
            return Verdict.no("playerutils.exempt", "action", action.word());
        }
        return Verdict.YES;
    }

    @Override
    public String describe() {
        return "who may point which action at whom, exemption included";
    }
}
