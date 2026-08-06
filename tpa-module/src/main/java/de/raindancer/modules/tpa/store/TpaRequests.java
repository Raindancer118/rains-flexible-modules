package de.raindancer.modules.tpa.store;

import de.raindancer.modules.tpa.model.TpaKind;
import de.raindancer.modules.tpa.model.TpaRequest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Who has asked whom.
 *
 * <h2>Keyed by the asker, and only by the asker</h2>
 * One map, keyed by whoever did the asking, because a player has at most one outgoing request. Incoming
 * is a scan of that map rather than a second index — which sounds wasteful and is not: there are as many
 * requests on a server as there are people mid-conversation, and a second index is a second thing to
 * keep in step. The old plugin did exactly this and it was right.
 *
 * <h2>The two rules that are not symmetrical</h2>
 * <b>One outgoing, uncapped incoming.</b> Asking is something you do; being asked is something that
 * happens to you, and capping that would let one person block everybody else from reaching somebody by
 * asking first. And a second request to a <em>different</em> person displaces the first — a change of
 * mind — while a second to the <em>same</em> person is refused, because that is not a change of mind,
 * it is asking twice.
 *
 * <h2>Expiry is asked, never swept</h2>
 * Every read filters out what has run out. {@link #expire} exists only to <em>report</em> what lapsed so
 * both sides can be told, and a sweep that has not fired yet can therefore never let somebody accept a
 * request that ended. The old plugin scheduled its sweep at exactly the expiry, the task landed
 * milliseconds early, and requests sat there unanswerable with nobody told — found by a pair of bots on
 * a live server.
 *
 * <h2>Thread safety</h2>
 * Safe from any thread, and it has to be: on Folia a command and a menu click for the same request can
 * arrive on two region threads. Taking a request is one {@code remove}, so two threads cannot both
 * promise the same teleport.
 */
public final class TpaRequests {

    private final Map<UUID, TpaRequest> outgoing = new ConcurrentHashMap<>();
    /** Milliseconds; injected so expiry can be tested without waiting a minute. */
    private final LongSupplier clock;

    private volatile Duration standingFor = Duration.ofSeconds(60);

    public TpaRequests() {
        this(System::currentTimeMillis);
    }

    public TpaRequests(LongSupplier clock) {
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** How long a request stands before it lapses. */
    public void standingFor(Duration howLong) {
        if (howLong != null && !howLong.isZero() && !howLong.isNegative()) {
            this.standingFor = howLong;
        }
    }

    public Duration standingFor() {
        return standingFor;
    }

    // ------------------------------------------------------------------------ asking

    /**
     * Makes a request, displacing whatever else this player had asked.
     *
     * @return the request, or empty when it was refused — asking themselves, asking somebody they have
     *         already asked, or asking nobody at all
     */
    public Optional<TpaRequest> put(UUID from, UUID to, TpaKind kind) {
        return make(from, to, kind).request();
    }

    /**
     * The same, reporting what it displaced instead.
     *
     * <p>Two methods over one returning both, because nearly every caller wants one or the other and a
     * caller that ignored the displaced one would leave somebody waiting for an answer to a request
     * that no longer exists.
     *
     * @return the request this one pushed aside, or empty when there was none
     */
    public Optional<TpaRequest> displacedBy(UUID from, UUID to, TpaKind kind) {
        return make(from, to, kind).displaced();
    }

    /**
     * Both at once: the request made, and whatever it pushed aside.
     *
     * <p>For the one caller that needs both sides of the same asking. Calling {@link #put} and
     * {@link #displacedBy} back to back would be two separate operations on the same state — the first
     * already stores the request, so the second finds it sitting there for that same target and reports
     * "asking the same person again", coming back empty even though nothing was actually asked before.
     */
    public Outcome ask(UUID from, UUID to, TpaKind kind) {
        return make(from, to, kind);
    }

    /** What one asking came to: the request made, and whatever it pushed aside. */
    public record Outcome(Optional<TpaRequest> request, Optional<TpaRequest> displaced) {

        static Outcome refused() {
            return new Outcome(Optional.empty(), Optional.empty());
        }
    }

    private Outcome make(UUID from, UUID to, TpaKind kind) {
        if (from == null || to == null || kind == null || from.equals(to)) {
            return Outcome.refused();
        }
        long now = clock.getAsLong();
        TpaRequest[] made = new TpaRequest[1];
        TpaRequest[] pushedAside = new TpaRequest[1];

        // One operation, so two commands arriving together cannot both make a request — on Folia they
        // really can be two threads, and two outgoing requests is two teleports promised.
        outgoing.compute(from, (ignored, held) -> {
            if (held != null && !held.isExpired(now)) {
                if (held.to().equals(to)) {
                    // Asking the same person again. Not a change of mind: the answer they have not
                    // given yet is still coming.
                    return held;
                }
                pushedAside[0] = held;
            }
            made[0] = new TpaRequest(from, to, kind, now, now + standingFor.toMillis());
            return made[0];
        });
        return new Outcome(Optional.ofNullable(made[0]), Optional.ofNullable(pushedAside[0]));
    }

    // ------------------------------------------------------------------------ looking

    /** What this player has asked, if it still stands. */
    public Optional<TpaRequest> from(UUID asker) {
        if (asker == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(outgoing.get(asker))
                .filter(request -> !request.isExpired(clock.getAsLong()));
    }

    /** Everything asked of this player, newest first — what a list shows and what bare accept takes. */
    public List<TpaRequest> to(UUID asked) {
        if (asked == null) {
            return List.of();
        }
        long now = clock.getAsLong();
        return outgoing.values().stream()
                .filter(request -> request.to().equals(asked))
                .filter(request -> !request.isExpired(now))
                // Newest first, and the asker's id breaks a tie. Two requests can land in the same
                // millisecond, and a comparator that called them equal would leave the order to
                // whatever the map happened to iterate — so bare /tpaccept would take an arbitrary one
                // of the two, and not necessarily the one at the top of the menu.
                .sorted(Comparator.comparingLong(TpaRequest::madeAt).reversed()
                        .thenComparing(request -> request.from().toString()))
                .toList();
    }

    /** Whether this player has already asked that one. */
    public boolean has(UUID asker, UUID asked) {
        return from(asker).map(request -> request.to().equals(asked)).orElse(false);
    }

    /** How many stand right now, for a diagnostic and for the tests. */
    public int count() {
        long now = clock.getAsLong();
        return (int) outgoing.values().stream().filter(request -> !request.isExpired(now)).count();
    }

    // ------------------------------------------------------------------------ answering

    /**
     * Takes a request so it can be answered.
     *
     * <p>One {@code remove}, so two people clicking at once cannot both be told the teleport is
     * theirs.
     *
     * @param from who asked, or null for whichever is newest — which is what bare {@code /tpaccept}
     *             means, since the one that just appeared on screen is the one being answered
     */
    public Optional<TpaRequest> take(UUID asked, UUID from) {
        if (asked == null) {
            return Optional.empty();
        }
        TpaRequest wanted = from != null
                ? from(from).filter(request -> request.to().equals(asked)).orElse(null)
                : to(asked).stream().findFirst().orElse(null);
        if (wanted == null) {
            return Optional.empty();
        }
        // Only if it is still the one we looked at: between the read and here, they may have withdrawn
        // it and asked somebody else.
        return outgoing.remove(wanted.from(), wanted) ? Optional.of(wanted) : Optional.empty();
    }

    /** The asker taking their own request back. */
    public Optional<TpaRequest> withdraw(UUID asker) {
        return from(asker).filter(request -> outgoing.remove(asker, request));
    }

    // ------------------------------------------------------------------------ ending

    /**
     * Everything that has run out, removed and handed back so both sides can be told.
     *
     * <p>Reporting only. Nothing depends on this having run — every read already filters expired ones
     * out, which is what makes a late sweep harmless rather than a request nobody can answer.
     */
    public List<TpaRequest> expire() {
        long now = clock.getAsLong();
        List<TpaRequest> lapsed = new ArrayList<>();
        for (TpaRequest request : List.copyOf(outgoing.values())) {
            if (request.isExpired(now) && outgoing.remove(request.from(), request)) {
                lapsed.add(request);
            }
        }
        return List.copyOf(lapsed);
    }

    /**
     * Everything involving this player, removed and handed back. Called when they log out.
     *
     * <p>Both directions. Their own request goes because there is nobody left to travel; the ones asked
     * of them go because otherwise those people wait out a full minute for an answer from somebody who
     * is not there.
     */
    public List<TpaRequest> forget(UUID player) {
        if (player == null) {
            return List.of();
        }
        List<TpaRequest> ended = new ArrayList<>();
        long now = clock.getAsLong();
        for (TpaRequest request : List.copyOf(outgoing.values())) {
            boolean theirs = request.from().equals(player) || request.to().equals(player);
            if (!outgoing.remove(request.from(), request)) {
                continue;
            }
            if (!theirs) {
                // Somebody else's, and expired — swept along the way because we are already walking
                // the map. Nobody here to tell about it: expire() is what reports those, and it will
                // simply find one fewer.
                if (!request.isExpired(now)) {
                    // Not expired and not theirs: put it straight back. Only reachable if another
                    // thread is mid-change, and taking it would end a request nobody asked to end.
                    outgoing.putIfAbsent(request.from(), request);
                }
                continue;
            }
            // Reported whether or not it had just expired. The other side is still standing there
            // holding a request that is now gone, and "they left" is the true and useful thing to say
            // — where saying nothing leaves them waiting to answer something that no longer exists.
            ended.add(request);
        }
        return List.copyOf(ended);
    }

    /** Forgets everybody. For a plugin being disabled. */
    public void clear() {
        outgoing.clear();
    }
}
