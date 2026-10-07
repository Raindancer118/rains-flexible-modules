package de.raindancer.modules.essentials.rules;

import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.essentials.model.Wear;

/** Whether something may be repaired, and which items count as worth it. */
public final class RepairRule extends AbstractRule<RepairRule.Request> {

    public enum Scope {
        HAND, ALL
    }

    public RepairRule() {
        super("only a worn item is repaired, a whole inventory needs its own permission, and somebody "
                + "else's needs another");
    }

    /**
     * @param mayAll holds the node for repairing everything at once
     * @param held   the item in the main hand; ignored when the scope is the whole inventory
     */
    public record Request(Scope scope, boolean self, boolean mayOthers, boolean mayAll, Wear held) {
    }

    @Override
    public Verdict judge(Request request) {
        if (!request.self() && !request.mayOthers()) {
            return Verdict.refused("essentials.repair.not-others");
        }
        if (request.scope() == Scope.ALL) {
            return request.mayAll() ? Verdict.allowed() : Verdict.refused("essentials.repair.not-all");
        }
        Wear held = request.held();
        if (!held.holding()) {
            return Verdict.refused("essentials.repair.nothing-held");
        }
        if (!held.damageable()) {
            return Verdict.refused("essentials.repair.not-damageable");
        }
        if (held.damage() <= 0) {
            return Verdict.refused("essentials.repair.already-fine");
        }
        return Verdict.allowed();
    }

    public static boolean needsRepair(Wear wear) {
        return wear.holding() && wear.damageable() && wear.damage() > 0;
    }
}
