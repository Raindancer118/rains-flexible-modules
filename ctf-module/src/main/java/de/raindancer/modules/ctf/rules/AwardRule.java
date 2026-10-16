package de.raindancer.modules.ctf.rules;

import de.raindancer.modules.ctf.model.Award;
import de.raindancer.modules.ctf.model.Fighter;

import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToLongFunction;

/** Hands out the end-of-round awards. Ties go to fewer deaths, then to the name — never to chance. */
public final class AwardRule implements ICtfRule {

    public Map<Award, UUID> awards(Collection<Fighter> fighters) {
        Map<Award, UUID> awards = new EnumMap<>(Award.class);
        best(fighters, Fighter::score).ifPresent(id -> awards.put(Award.MVP, id));
        best(fighters, fighter -> fighter.returns() * 2L + fighter.defenceKills() + fighter.clutchReturns() * 2L)
                .ifPresent(id -> awards.put(Award.BEST_DEFENDER, id));
        best(fighters, fighter -> fighter.fastestCapture().isPresent()
                ? Long.MAX_VALUE / 2 - fighter.fastestCapture().getAsLong() : 0)
                .ifPresent(id -> awards.put(Award.SPEED_DEMON, id));
        best(fighters, Fighter::drops).ifPresent(id -> awards.put(Award.BUTTERFINGERS, id));
        best(fighters, Fighter::escortKills).ifPresent(id -> awards.put(Award.BODYGUARD, id));
        best(fighters, Fighter::carryMillis).ifPresent(id -> awards.put(Award.FLAG_RUNNER, id));
        return awards;
    }

    private static Optional<UUID> best(Collection<Fighter> fighters, ToLongFunction<Fighter> by) {
        return fighters.stream()
                .filter(fighter -> by.applyAsLong(fighter) > 0)
                .min(Comparator.comparingLong((Fighter fighter) -> -by.applyAsLong(fighter))
                        .thenComparingInt(Fighter::deaths)
                        .thenComparing(Fighter::name))
                .map(Fighter::id);
    }
}
