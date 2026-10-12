package de.raindancer.modules.guilds.model;

import java.util.Objects;

/**
 * One of a guild's goals for one week: do {@code goal} of something, and the guild earns {@code reward}
 * experience in one go.
 *
 * @param week   weeks since the epoch, starting on Mondays — see {@code ContractRule.weekOf}
 * @param what   empty for "any" — any monster, any crop; otherwise what an {@link Activity} names
 * @param title  how it reads: "Kill zombies", "Mine iron ore"
 */
public record Contract(String guild, long week, int slot, XpSource source, String what, String title,
                       long goal, long progress, long reward, boolean done) {

    public Contract {
        Objects.requireNonNull(guild, "guild");
        Objects.requireNonNull(source, "source");
        what = what == null ? "" : what;
        title = title == null ? source.title() : title;
        goal = Math.max(1, goal);
        progress = Math.max(0, Math.min(goal, progress));
    }

    /** Whether that activity counts towards this contract. */
    public boolean counts(Activity activity) {
        return !done && activity.source() == source && (what.isEmpty() || what.equals(activity.what()));
    }

    /** The same contract with {@code more} done, finished when it reaches the goal. */
    public Contract advanced(long more) {
        long now = Math.min(goal, progress + Math.max(0, more));
        return new Contract(guild, week, slot, source, what, title, goal, now, reward, now >= goal);
    }

    public double fraction() {
        return (double) progress / goal;
    }
}
