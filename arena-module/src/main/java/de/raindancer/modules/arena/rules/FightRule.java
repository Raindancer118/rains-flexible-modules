package de.raindancer.modules.arena.rules;

import de.raindancer.core.world.combat.Attack;
import de.raindancer.core.world.combat.Verdict;

import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Who may hurt whom in the arena's worlds, as Core's {@code Combat} asks it: only the two sides of a running fight,
 * each other. The hub is somewhere nobody is hurt, which is also what stops a fight being joined from the stands.
 */
public final class FightRule implements IArenaRule {

    /**
     * @param fighting whether these two are on opposite sides of the same running fight (a mob counts as the side of
     *                 the wave it belongs to)
     * @return null for "not the arena's business"
     */
    public Verdict verdict(Attack attack, Predicate<String> arenaWorld, BiPredicate<UUID, UUID> fighting) {
        if (!arenaWorld.test(attack.world())) {
            return null;
        }
        boolean aPlayer = attack.attacker() == Attack.Fighter.PLAYER || attack.victim() == Attack.Fighter.PLAYER;
        if (!aPlayer) {
            return null;
        }
        return fighting.test(attack.attackerId(), attack.victimId()) ? Verdict.ALLOWED : Verdict.PROTECTED;
    }

    @Override
    public String describe() {
        return "only the two sides of a running fight hurt each other in the arena";
    }
}
