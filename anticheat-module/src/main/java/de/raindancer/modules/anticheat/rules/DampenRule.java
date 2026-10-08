package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.model.Category;
import de.raindancer.modules.anticheat.model.CheckType;

import java.util.Map;

/**
 * Mitigation instead of only detection: while somebody is clearly suspected in combat — a combat
 * check at twice its alert level, or Improbable — their hits do less. Nothing is announced to them;
 * a cheat that wins fights stops winning them while a human looks.
 */
public final class DampenRule implements IAntiCheatRule {

    public double multiplier(Map<CheckType, Double> levels, int percent) {
        for (Map.Entry<CheckType, Double> entry : levels.entrySet()) {
            CheckType check = entry.getKey();
            boolean combat = check.category() == Category.COMBAT && !check.experimental();
            if (check == CheckType.IMPROBABLE && entry.getValue() >= 1
                    || combat && entry.getValue() >= check.alertAt() * 2.0) {
                return Math.max(0, Math.min(100, percent)) / 100.0;
            }
        }
        return 1.0;
    }

    @Override
    public String describe() {
        return "how much a suspected player's hits are dampened";
    }
}
