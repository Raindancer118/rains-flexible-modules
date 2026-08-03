package de.raindancer.modules.moderation.service;

import de.raindancer.modules.moderation.ModerationSettings;

/**
 * Something that <em>does</em> the things a moderator asked for.
 *
 * <p>The counterpart to {@link de.raindancer.modules.moderation.rules.IModerationRule}: a rule decides
 * and changes nothing, a service changes things and decides as little as possible. Handing out a
 * punishment, closing a report, writing a note, routing a line of staff chat — each is an action with an
 * effect, and each asks a rule first rather than inventing its own answer.
 *
 * <h2>What implementing this promises</h2>
 * <ul>
 *   <li><b>It reads its settings through {@link #settings(ModerationSettings)}</b> rather than holding a
 *       live view. A snapshot means nothing has to reason about a value changing halfway through, and
 *       swapping the whole snapshot means a reload takes effect on the next event rather than the next
 *       restart. Every service takes it <em>whether or not it currently reads anything from the
 *       file</em>, because the one that is forgotten when it starts reading something is the one that
 *       keeps yesterday's numbers until the next restart — and that gets reported as "the config does
 *       not work".</li>
 *   <li><b>It asks rather than decides.</b> A service that works out for itself who may be punished is
 *       a second set of rules, and the second set is always the one that is wrong.</li>
 *   <li><b>It is safe to call from any thread, or it schedules.</b> Half of what reaches these arrives
 *       from a chat event, which Paper fires asynchronously.</li>
 * </ul>
 */
public interface IModerationService {

    /** Swaps in the settings as they are now. Called on reload. */
    void settings(ModerationSettings settings);

    /** What this service does, for the console line that lists what started. */
    default String describe() {
        String name = getClass().getSimpleName();
        return name.isEmpty() ? getClass().getName() : name;
    }
}
