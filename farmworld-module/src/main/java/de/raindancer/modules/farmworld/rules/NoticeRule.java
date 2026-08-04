package de.raindancer.modules.farmworld.rules;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * When to tell the server that a farm world is about to be regenerated.
 *
 * <h2>Why the warning is a feature and not a courtesy</h2>
 * RainsCore regenerates a farm world on its schedule, moves whoever is standing in it to spawn and says
 * one line as it happens. That is correct and it is not enough: somebody two hours into a mining trip,
 * with a base, a full set of chests and an unfinished tunnel, finds out at the moment all of it stops
 * existing. Nothing was lost that the farm world did not promise to lose — but a promise nobody was
 * reminded of is one people report as a bug, and the first thing they ask for is the warning.
 *
 * <p>So the module warns and Core deletes. Deliberately that way round: the module knows nothing about
 * deleting a world and Core knows nothing about what this server wants to say.
 *
 * <h2>Why the fixed warnings and not only the owner's number</h2>
 * The owner picks how far ahead the first warning goes, because that is a judgement about their players
 * — an hour on a server where trips are long, ten minutes on one where they are not. The last two are
 * fixed at five minutes and one minute, because those are not a judgement: they are "start walking
 * back" and "put it in an ender chest", and a server configured to warn once an hour ahead and never
 * again has effectively not warned at all.
 *
 * <h2>Why the rule remembers nothing</h2>
 * There is one of these for the whole server and it is asked about every farm world in turn. A rule
 * that kept "what I have already said" would say each warning once across all of them — the first farm
 * world would get its notice and the rest would be silently skipped. Remembering is the service's job,
 * one entry per farm world; deciding is this one's, and it is a function of its arguments.
 */
public final class NoticeRule implements IFarmWorldRule {

    /**
     * The two warnings no server goes without.
     *
     * <p>Ordered as they are given. Five minutes is "start walking back", one minute is "put what you
     * are carrying somewhere it survives".
     */
    public static final List<Duration> ALWAYS =
            List.of(Duration.ofMinutes(5), Duration.ofMinutes(1));

    /** The longest notice an owner may ask for. A day ahead is a notice nobody remembers hearing. */
    public static final Duration LONGEST = Duration.ofDays(1);

    private final Duration firstWarning;

    /**
     * @param firstWarning how far ahead the owner's own warning goes; null, zero or negative means
     *                     only the two fixed ones are given
     */
    public NoticeRule(Duration firstWarning) {
        this.firstWarning = firstWarning == null || firstWarning.isNegative() || firstWarning.isZero()
                ? null
                : (firstWarning.compareTo(LONGEST) > 0 ? LONGEST : firstWarning);
    }

    /**
     * Every point at which something is said, longest first.
     *
     * <p>De-duplicated, so an owner who sets their warning to exactly five minutes gets one notice at
     * five minutes rather than two identical lines in the same second.
     */
    public List<Duration> leads() {
        Set<Duration> found = new LinkedHashSet<>();
        if (firstWarning != null) {
            found.add(firstWarning);
        }
        for (Duration fixed : ALWAYS) {
            // Only the ones that are actually earlier than the trip is long. A fixed five-minute
            // warning on a farm world regenerated every two minutes would fire on the same tick as the
            // regeneration, which is a line nobody can act on.
            if (firstWarning == null || fixed.compareTo(firstWarning) < 0) {
                found.add(fixed);
            }
        }
        List<Duration> ordered = new ArrayList<>(found);
        ordered.sort(java.util.Comparator.reverseOrder());
        return List.copyOf(ordered);
    }

    /**
     * Which warning should be given right now, if any.
     *
     * <p>The <b>tightest</b> lead the time left has fallen inside and that has not already been given —
     * not the widest. In an ordinary countdown the two are the same thing, because the timer looks often
     * enough to catch each lead as it is crossed. They come apart in exactly one case, and it is the case
     * that matters: a server that was busy or was restarted, so that the first look at a farm world finds
     * ninety seconds left with nothing said yet.
     *
     * <p>Answering with the widest lead there — fifteen minutes, because ninety seconds is inside it —
     * records fifteen minutes as given and then fires the five- and one-minute notices on the next two
     * looks: three announcements in a minute, the first two already out of date. Answering with the
     * tightest gives the five-minute notice now and the one-minute notice a minute later, which is what a
     * countdown that had not been missed would have done.
     *
     * <p>The lead is what the caller <em>records</em>, and deliberately not what it says: the sentence
     * carries the time actually left, so a notice that fires a few seconds late is a few seconds out rather
     * than wrong by the width of a lead. See {@code NoticeService}.
     *
     * @param left        how long until it is made again; null means it is due now, which is not a warning —
     *                    the thing being warned about is already happening
     * @param alreadySaid the tightest lead already announced for this farm world, or null for none
     * @return the lead to record, or empty when there is nothing to say
     */
    public Optional<Duration> dueNow(Duration left, Duration alreadySaid) {
        if (left == null || left.isNegative() || left.isZero()) {
            return Optional.empty();
        }
        Duration tightest = null;
        for (Duration lead : leads()) {
            if (left.compareTo(lead) > 0) {
                continue;   // not yet inside this one
            }
            if (alreadySaid != null && lead.compareTo(alreadySaid) >= 0) {
                continue;   // this notice, or a wider one, has already gone out
            }
            // leads() is widest first, so every match after this one is tighter. Kept rather than returned.
            tightest = lead;
        }
        return Optional.ofNullable(tightest);
    }

    /**
     * Whether a countdown has restarted and what was said about the last one should be forgotten.
     *
     * <p>Asked rather than worked out from a regeneration having happened, because a farm world can also
     * be made again by hand, have its schedule changed, or be created fresh — and all three leave a
     * remembered "I already said five minutes" that would silence the next countdown's warnings
     * entirely. More time left than the longest lead means a new countdown, whatever caused it.
     */
    public boolean hasStartedOver(Duration left) {
        if (left == null) {
            return false;
        }
        List<Duration> leads = leads();
        return leads.isEmpty() || left.compareTo(leads.getFirst()) > 0;
    }

    @Override
    public String describe() {
        return "when to warn that a farm world is about to be made again";
    }
}
