package de.raindancer.modules.hungergames.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Being hit while a medikit is being applied stops it being applied.
 *
 * <h2>Why a listener and not an ability</h2>
 * Core has an {@code ItemTrigger.DAMAGE_TAKEN} that reads like the fit, and is not: the medikit is not in
 * anybody's hand by the time the hit lands — it is in a countdown that a service is holding. So what is
 * needed is the plain fact that this player took damage, which is this and nothing else.
 * {@code MedikitCountdownService.interrupt} makes every real decision, including whether there was anything
 * to interrupt.
 *
 * <h2>Why MONITOR, ignoring cancelled, and only real damage</h2>
 * The source's own listener used exactly this shape. {@link EventPriority#MONITOR} because this changes
 * nothing about the hit and must see it as it actually resolved — after armour, after a shield, after
 * whatever plugin reduced it. {@code ignoreCancelled} because a blow another plugin cancelled never landed,
 * and cancelling somebody's treatment for a hit that did not happen is the sort of thing reported as "my
 * medikit randomly stops working". And {@code getFinalDamage() > 0} because a hit fully absorbed is a hit
 * the player did not feel: a shield block that stopped everything must not cost them the heal.
 */
public final class MedikitInterruptListener implements IHungerGamesListener {

    /** Cancelling a treatment. @return whether there was one, so nothing is said when there was not. */
    private final Predicate<UUID> interrupt;

    public MedikitInterruptListener(Predicate<UUID> interrupt) {
        this.interrupt = interrupt;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && event.getFinalDamage() > 0) {
            interrupt.test(player.getUniqueId());
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered here: the pending treatments are the countdown service's, and it is told
        // separately. A copy of that set kept here would be a second answer to "is this player being
        // treated", and the two would disagree the first time one of them was updated alone.
    }

    @Override
    public String describe() {
        return "cancelling a medikit's wind-up when its holder is hit";
    }
}
