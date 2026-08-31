package de.raindancer.modules.manhunt.service;

import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.ManhuntSettings.RunnerDeathRule;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What a Runner's death costs them, and who is still standing. Bukkit-free, like everything in this
 * module that decides rather than converts — {@code ManhuntDeathListener} turns a
 * {@code PlayerDeathEvent} into one {@link #record} call and acts on the verdict.
 *
 * <h2>Three rules, because a Manhunt is not one game</h2>
 * {@link RunnerDeathRule#RESPAWN} is the long chase where dying costs time and gear and nothing else.
 * {@link RunnerDeathRule#ELIMINATE} is the classic: one death and the Runner is out, which with a
 * single Runner is simply "the Hunters won". {@link RunnerDeathRule#LIVES} is the middle, for a
 * server that wants a Runner to survive a mistake but not five.
 *
 * <h2>Why the count is deaths taken, not lives remaining</h2>
 * Lives remaining would have to be re-seeded for everybody the moment an owner changes
 * {@link ManhuntSettings#runnerLives()} mid-hunt, and a Runner who had already died would either gain
 * a life or lose one depending on which way it moved. Counting deaths and subtracting means the
 * setting is read fresh on every question, the same "re-derive rather than cache" rule
 * {@link ManhuntLobbyBox} and {@link TrackerCompass} both document for themselves.
 */
public final class ManhuntLives {

    /** What a death cost the Runner who took it. */
    public enum Verdict { RESPAWNED, ELIMINATED }

    /** Deaths taken this hunt, per Runner. */
    private final Map<UUID, Integer> deaths = new ConcurrentHashMap<>();

    private volatile ManhuntSettings settings;

    public ManhuntLives(ManhuntSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** Told the live settings whenever they change — wired via {@code SettingsStore.onChange}. */
    public void settings(ManhuntSettings fresh) {
        this.settings = fresh;
    }

    /** Records one death and answers what it cost. */
    public Verdict record(UUID runner) {
        if (runner == null) {
            return Verdict.RESPAWNED;
        }
        int taken = deaths.merge(runner, 1, Integer::sum);
        return taken >= allowance() ? Verdict.ELIMINATED : Verdict.RESPAWNED;
    }

    /** How many deaths this Runner has left before they are out. Zero once they are. */
    public int livesLeft(UUID runner) {
        return Math.max(0, allowance() - deaths.getOrDefault(runner, 0));
    }

    /** Whether this Runner is out of the hunt for good. Never true under {@link RunnerDeathRule#RESPAWN}. */
    public boolean isOut(UUID runner) {
        return livesLeft(runner) == 0 && settings.runnerDeathRule() != RunnerDeathRule.RESPAWN;
    }

    /**
     * Whether every Runner on {@code roster} is out — the Hunters' win, under any rule that can put a
     * Runner out at all. An empty roster is deliberately not a win: there was nobody to beat, and a
     * hunt that never had a Runner should not end itself the moment it starts.
     */
    public boolean allOut(Set<UUID> roster) {
        if (roster == null || roster.isEmpty()) {
            return false;
        }
        return roster.stream().allMatch(this::isOut);
    }

    /** Whoever on {@code roster} is still in the hunt, in the order given — for the narration. */
    public Set<UUID> stillIn(Set<UUID> roster) {
        Set<UUID> standing = new LinkedHashSet<>();
        for (UUID runner : roster) {
            if (!isOut(runner)) {
                standing.add(runner);
            }
        }
        return standing;
    }

    /** A fresh hunt: everybody back on full lives. */
    public void reset() {
        deaths.clear();
    }

    /** How many deaths a Runner is allowed before being out — {@link Integer#MAX_VALUE} for none. */
    private int allowance() {
        return switch (settings.runnerDeathRule()) {
            case RESPAWN -> Integer.MAX_VALUE;
            case ELIMINATE -> 1;
            case LIVES -> settings.runnerLivesClamped();
        };
    }
}
