package de.raindancer.modules.mannequin.rules;

/**
 * Whether a mannequin holding a shield should raise it right now.
 *
 * <p>Everything this needs is handed in rather than read from the entity, so the periodic check in
 * {@code MannequinModule}'s scheduled timer can ask it once per mannequin per tick without this
 * class touching Bukkit at all.
 */
public final class ShieldBlockRule implements IMannequinRule {

    /**
     * @param hasShield       whether the off hand currently holds a shield
     * @param blockingEnabled the owner's own switch — {@code MannequinSettings#blockingEnabled}
     * @param alreadyBlocking whether {@code LivingEntity#isHandRaised()} already says yes
     * @param attackerNearby  whether a player is within the configured range right now
     */
    public boolean shouldRaiseShield(boolean hasShield, boolean blockingEnabled,
                                     boolean alreadyBlocking, boolean attackerNearby) {
        return hasShield && blockingEnabled && attackerNearby && !alreadyBlocking;
    }

    @Override
    public String describe() {
        return "whether a mannequin holding a shield should raise it against a nearby attacker";
    }
}
