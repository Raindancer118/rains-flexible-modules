package de.raindancer.modules.anticheat.rules;

import de.raindancer.modules.anticheat.model.CheckType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Many checks, each failed a little. A full cheat client trips reach, hitbox, clicks and movement at
 * once, often each below its own alert level; honest lag trips one or two. Each counted check adds
 * its level as a share of its kick level (or 30 where it has none), capped at one.
 */
public final class ImprobableRule implements IAntiCheatRule {

    public static final double THRESHOLD = 1.5;
    public static final int FEWEST_CHECKS = 3;

    public Judgement judge(Map<CheckType, Double> levels) {
        double score = 0;
        List<String> involved = new ArrayList<>();
        for (Map.Entry<CheckType, Double> entry : levels.entrySet()) {
            CheckType check = entry.getKey();
            if (check.experimental() || check == CheckType.IMPROBABLE || entry.getValue() < check.alertAt()) {
                continue;
            }
            double reference = check.kickAt() > 0 ? check.kickAt() : 30;
            score += Math.min(1, entry.getValue() / reference);
            involved.add(check.title());
        }
        if (involved.size() >= FEWEST_CHECKS && score >= THRESHOLD) {
            involved.sort(String::compareTo);
            return Judgement.fail(score, String.format(Locale.ROOT, "%d checks at once (%s), together %.2f",
                    involved.size(), String.join(", ", involved), score));
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether many checks failing a little at once add up";
    }
}
