package de.raindancer.modules.hungergames.listener;

import de.raindancer.modules.hungergames.model.GamePhase;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The protection period, enforced.
 *
 * <h2>The bug this class exists because of</h2>
 * The period was measured, announced, counted down on the boss bar and ended with a broadcast. Every part of it
 * worked except the part that stops a hit: {@code GameTimerService.isGraceActive()} answered correctly the
 * whole time, and <b>nothing ever asked it</b>. So a round told forty people they were safe for sixty seconds
 * and let the difficulty the arena had just been set to do what it does.
 *
 * <p>That is worse than not having the feature. Somebody who is told nothing takes cover; somebody who is told
 * they are protected walks into the open — which is the entire purpose of the announcement, and precisely what
 * got them killed. It was reported as "it says I am protected and zombies just kill me".
 *
 * <h2>What is refused, and the two things that are not</h2>
 * Everything the world can do — mobs, fire, drowning, suffocation, cactus, lava, falling, starvation — and
 * tributes hitting each other, which is the point rather than an extra: a protected opening makes the scramble
 * to the cornucopia a footrace instead of a fight at it.
 *
 * <p>Not refused:
 * <ul>
 *   <li><b>The void.</b> Cancelling it leaves somebody below the world for the rest of the round, unable to
 *       die out of it. A protection that traps you is not one.</li>
 *   <li><b>A gamemaster eliminating somebody by hand.</b> That goes through the session rather than through a
 *       damage event, so this never sees it — noted here because it is the obvious next question, and the
 *       answer is that a deliberate act by somebody who can see the arena is not something to second-guess.</li>
 * </ul>
 *
 * <h2>Why the decision is a plain method taking a cause name</h2>
 * {@link #wouldCancel} takes a UUID and a string, so the whole rule is testable without a server, an entity or
 * an event. That matters more here than anywhere else in the module: this is a rule about damage, and the way
 * to find out whether a damage rule is right must not be to stand in an arena and be bitten.
 */
public final class GracePeriodListener implements IHungerGamesListener {

    /**
     * The causes that are allowed through.
     *
     * <p>An allow-list of exceptions rather than a deny-list of what to stop, because the list of ways a
     * Minecraft world can hurt somebody grows every version — and a rule written as "stop these fourteen
     * things" silently stops covering the fifteenth. Anything new is refused by default, which is the safe
     * direction for a protection.
     */
    private static final Set<String> STILL_KILLS = Set.of("VOID", "KILL", "CUSTOM");

    private final BooleanSupplier graceRunning;
    private final Supplier<GamePhase> phase;
    private final Predicate<UUID> isTribute;

    /**
     * @param graceRunning whether the protection period is currently running — {@code GameTimerService}'s own
     *                     answer, asked rather than duplicated so the two cannot disagree about when it ends
     * @param isTribute    whether that person is in the round at all; a spectator or a staff member is not
     */
    public GracePeriodListener(BooleanSupplier graceRunning, Supplier<GamePhase> phase,
                               Predicate<UUID> isTribute) {
        this.graceRunning = graceRunning;
        this.phase = phase;
        this.isTribute = isTribute;
    }

    @Override
    public void forget(UUID player) {
        // Nothing is remembered. Whether somebody is protected is read from the round at the moment they are
        // hit, which is what makes it correct for a tribute who rejoins mid-grace.
    }

    /**
     * Whether a hit on that person, for that reason, must be refused.
     *
     * <p>The whole rule, as a function. Public so it can be checked without a server — see the class note on
     * why that matters for this rule in particular.
     *
     * @param cause the damage cause's name, as Bukkit spells it
     */
    public boolean wouldCancel(UUID who, String cause) {
        // The phase first, because it is the cheapest check and this method is called for every hit on the
        // server. Only a running round has a protection period; the lobby has LobbyListener, and two handlers
        // cancelling the same hit for different reasons is how one of them silently stops mattering.
        if (phase.get() != GamePhase.RUNNING) {
            return false;
        }
        if (!graceRunning.getAsBoolean()) {
            return false;
        }
        if (!isTribute.test(who)) {
            return false;
        }
        return !STILL_KILLS.contains(cause == null ? "" : cause.toUpperCase(Locale.ROOT));
    }

    /**
     * Cancels a hit during the protection period.
     *
     * <p>{@link EventPriority#LOW} so a plugin that genuinely means somebody to die — a punishment, an admin
     * kill — can still override it at a later priority, and {@code ignoreCancelled = true} so this does not
     * argue with something that has already refused the hit for its own reasons.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player hurt)) {
            return;
        }
        if (wouldCancel(hurt.getUniqueId(), event.getCause().name())) {
            event.setCancelled(true);
        }
    }

    @Override
    public String describe() {
        return "refusing every hit while the protection period is running";
    }
}
