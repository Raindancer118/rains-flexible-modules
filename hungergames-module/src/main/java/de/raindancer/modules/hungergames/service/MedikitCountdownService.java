package de.raindancer.modules.hungergames.service;

import de.raindancer.modules.hungergames.HungerGamesSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The medikit's wind-up: it heals a few seconds after it is used, and any damage in between cancels it.
 *
 * <h2>Why this exists, and what it changes back</h2>
 * The source ran the medikit on {@code items.medikit.countdown-seconds} — three by default — and cancelled
 * the treatment on any damage at all ({@code CustomItems.abortMedikitOnDamage}). The port healed the instant
 * the item was clicked, and its own javadoc admitted it: "the countdown itself needs a scheduler and a damage
 * listener, neither of which this class may touch".
 *
 * <p>That is not a small difference. The medikit is the most valuable thing in the sponsor shop and the wind-up
 * is the entire price of it: with it, using one in a fight is a gamble that the person hitting you misses for
 * three seconds, and the counterplay to somebody drinking it is to keep hitting them. Without it, it is a free
 * full heal mid-swing, and a fight against somebody carrying two of them cannot be won by fighting.
 *
 * <h2>Why it is a service with two seams rather than a lambda in the wiring</h2>
 * Everything interesting about it is a state machine — who is waiting, for how long, what a second does, what
 * damage does, what happens if they log out halfway — and every one of those is a rule somebody can get wrong
 * silently. Behind {@link Ticker} and {@link Treatment} it is drivable by hand, so all of it is decided in
 * tests rather than in a round.
 *
 * <h2>One timer for everybody, not one per player</h2>
 * The source started a {@code BukkitRunnable} per medikit. Forty tributes in a deathmatch is forty timers,
 * each of which has to remember to cancel itself — and the one that does not is a task ticking for a player
 * who logged out an hour ago. Here there is one, it starts when the first treatment does and stops when the
 * last one ends, which is a thing that can be asserted.
 */
public final class MedikitCountdownService implements IHungerGamesService {

    /** Runs {@code task} once a second until closed. The one thing here that needs a live server. */
    @FunctionalInterface
    public interface Ticker {
        AutoCloseable everySecond(Runnable task);
    }

    /** Everything this has to say or do in the world, so none of it is decided here. */
    public interface Treatment {

        /** Whether there is still somebody to treat — online, and not dead. */
        boolean stillThere(UUID holder);

        /** "Medikit applied — it works in <n>s. Taking damage cancels it." Said once, in chat. */
        void applied(UUID holder, int seconds);

        /** The count, on the action bar, once a second. */
        void counting(UUID holder, int secondsLeft);

        /** They already have one going, and a second click must not start another. */
        void alreadyRunning(UUID holder);

        /** They were hit. The item was never spent, so it is still theirs. */
        void interrupted(UUID holder);

        /**
         * Takes one medikit out of their inventory and heals them.
         *
         * @return whether there was one to take — false if they dropped it, gave it away or died holding it,
         *         in which case nothing is healed and nothing is taken
         */
        boolean spendAndHeal(UUID holder);
    }

    private final Treatment treatment;
    private final Ticker ticker;

    /** Who is waiting, and how many more seconds they have. */
    private final Map<UUID, Integer> waiting = new ConcurrentHashMap<>();

    private HungerGamesSettings settings = HungerGamesSettings.DEFAULTS;
    private AutoCloseable running;

    public MedikitCountdownService(Treatment treatment, Ticker ticker) {
        this.treatment = treatment;
        this.ticker = ticker;
    }

    /** Never schedules anything. For tests, which drive {@link #tick} themselves. */
    public static Ticker manual() {
        return task -> () -> { };
    }

    @Override
    public void settings(HungerGamesSettings settings) {
        this.settings = settings;
    }

    /** How long a medikit takes to work right now. Zero means it works the instant it is used. */
    public int windUpSeconds() {
        return Math.max(0, settings.medikitCountdownSeconds());
    }

    /**
     * Starts one, if the server has the medikit tuned to have a wind-up at all.
     *
     * @return whether the click was answered here. {@code false} means there is no wind-up on this server and
     *         the caller should heal at once — the source's own {@code countdown <= 0} branch, kept because a
     *         server that has set it to zero has said the medikit is an instant heal and that is their call
     */
    public boolean begin(UUID holder) {
        int seconds = windUpSeconds();
        if (holder == null || seconds <= 0) {
            return false;
        }
        if (waiting.putIfAbsent(holder, seconds) != null) {
            // Already treating. Answered here rather than by starting a second one, which in the source's
            // per-player-task version would have left the first task ticking with nothing to finish.
            treatment.alreadyRunning(holder);
            return true;
        }
        treatment.applied(holder, seconds);
        treatment.counting(holder, seconds);
        startTicking();
        return true;
    }

    /**
     * A hit lands, so whoever is being treated stops being treated.
     *
     * <p>Any damage at all, exactly as the source had it — not only damage from a player. Burning while
     * drinking it is the same problem the wind-up exists to create, and a version that forgave fire would
     * make standing in lava the safe place to heal.
     *
     * @return whether a treatment was actually cancelled, so the caller can stay silent when it was not
     */
    public boolean interrupt(UUID holder) {
        if (holder == null || waiting.remove(holder) == null) {
            return false;
        }
        treatment.interrupted(holder);
        stopTickingIfIdle();
        return true;
    }

    /** Whether this player is waiting for a medikit right now. */
    public boolean isTreating(UUID holder) {
        return holder != null && waiting.containsKey(holder);
    }

    /** How many are waiting — the number the timer's existence should agree with. */
    public int treating() {
        return waiting.size();
    }

    /** Whether the one shared timer is running. Exactly when somebody is waiting, and never otherwise. */
    public boolean isTicking() {
        return running != null;
    }

    /**
     * One second.
     *
     * <p>The count is shown first and the heal happens on the tick <em>after</em> the last one shown, which
     * is the source's own order: a three-second medikit shows 3, 2, 1 and then heals, so what a player reads
     * is the time they still have to survive rather than the time already gone.
     */
    public void tick() {
        List<UUID> healed = new ArrayList<>();
        List<UUID> gone = new ArrayList<>();

        for (Map.Entry<UUID, Integer> each : waiting.entrySet()) {
            UUID holder = each.getKey();
            if (!treatment.stillThere(holder)) {
                gone.add(holder);
                continue;
            }
            int left = each.getValue() - 1;
            if (left > 0) {
                each.setValue(left);
                treatment.counting(holder, left);
            } else {
                healed.add(holder);
            }
        }

        gone.forEach(waiting::remove);
        for (UUID holder : healed) {
            // Removed before healing, not after: spendAndHeal reaches into a live inventory, and a hit
            // arriving during it would otherwise find this player still listed and "interrupt" a treatment
            // that has already happened — cancelling nothing and telling them it did.
            waiting.remove(holder);
            treatment.spendAndHeal(holder);
        }
        stopTickingIfIdle();
    }

    /** They logged out or the round ended: forget them, without an interruption message nobody would read. */
    public void forget(UUID holder) {
        if (holder != null && waiting.remove(holder) != null) {
            stopTickingIfIdle();
        }
    }

    /** Drops every pending treatment — a round ending, or the module stopping. */
    public void clear() {
        waiting.clear();
        stopTickingIfIdle();
    }

    private void startTicking() {
        if (running == null) {
            running = ticker.everySecond(this::tick);
        }
    }

    private void stopTickingIfIdle() {
        if (running == null || !waiting.isEmpty()) {
            return;
        }
        try {
            running.close();
        } catch (Exception cannotCancel) {
            // Nothing useful to do: the handle is Core's scheduled task and a failure to cancel it leaves a
            // timer that finds an empty map and stops again next second.
        }
        running = null;
    }

    @Override
    public String describe() {
        return "the medikit's wind-up, and the damage that cancels it";
    }
}
