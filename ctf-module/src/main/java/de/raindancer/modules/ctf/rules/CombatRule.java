package de.raindancer.modules.ctf.rules;

import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Verdict;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Who may hurt whom in the Capture the Flag world, as Core's {@code Combat} asks it: players of different sides of the
 * running round; the same side only with friendly fire; nobody else, ever.
 */
public final class CombatRule implements ICtfRule {

    /**
     * @param sideOf the side of a player alive in the running round, empty for anybody else
     * @return null for "not Capture the Flag's business"
     */
    public Verdict verdict(Attack attack, Predicate<String> ctfWorld, Function<UUID, Optional<Integer>> sideOf,
                           boolean friendlyFire) {
        if (!ctfWorld.test(attack.world())) {
            return null;
        }
        if (!attack.isPlayerVersusPlayer()) {
            return attack.victim() == Attack.Fighter.PLAYER && sideOf.apply(attack.victimId()).isEmpty()
                    ? Verdict.PROTECTED : null;
        }
        if (attack.isSelfInflicted()) {
            return null;
        }
        Optional<Integer> attacker = sideOf.apply(attack.attackerId());
        Optional<Integer> victim = sideOf.apply(attack.victimId());
        if (attacker.isEmpty() || victim.isEmpty()) {
            return Verdict.PROTECTED;
        }
        if (attacker.get().equals(victim.get()) && !friendlyFire) {
            return Verdict.PROTECTED;
        }
        return Verdict.ALLOWED;
    }

    @Override
    public String describe() {
        return "only the sides of a running Capture the Flag round hurt each other";
    }
}
