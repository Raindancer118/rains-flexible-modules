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
 * <h2>Keyed by the pair</h2>
 * One map, keyed by asker and asked. A player asks one person, or — with {@code /tpahere @a} — a whole
 * batch at once; either way the batch is the asker's one outgoing "thing". Incoming is a scan of that map
 * rather than a second index — which sounds wasteful and is not: there are as many requests on a server as
 * there are people mid-conversation, and a second index is a second thing to keep in step.
 *
 * <h2>The two rules that are not symmetrical</h2>
 * <b>One outgoing, uncapped incoming.</b> Asking is something you do; being asked is something that
 * happens to you, and capping that would let one person block everybody else from reaching somebody by
 * asking first. And a second request to a <em>different</em> person displaces whatever the asker had
 * standing — a change of mind — while a second to the <em>same</em> person is refused, because that is not a
 * change of mind, it is asking twice.
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

    private record Key(UUID from, UUID to) {
    }

    private final Map<Key, TpaRequest> outgoing = new ConcurrentHashMap<>();
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

    /** What one asking came to: the request made, and the first one it pushed aside. */
    public record Outcome(Optional<TpaRequest> request, Optional<TpaRequest> displaced) {

        static Outcome refused() {
            return new Outcome(Optional.empty(), Optional.empty());
        }
    }

    /** What asking several people at once came to: the requests made, and every one it pushed aside. */
    public record Batch(List<TpaRequest> made, List<TpaRequest> displaced) {
    }

    private synchronized Outcome make(UUID from, UUID to, TpaKind kind) {
        if (from == null || to == null || kind == null || from.equals(to)) {
            return Outcome.refused();
        }
        long now = clock.getAsLong();
        Key key = new Key(from, to);
        TpaRequest held = outgoing.get(key);
        if (held != null && !held.isExpired(now)) {
            // Asking the same person again. Not a change of mind: the answer they have not given yet is
            // still coming.
            return new Outcome(Optional.empty(), Optional.empty());
        }
        List<TpaRequest> pushedAside = clearOthers(from, java.util.Set.of(to), now);
        TpaRequest made = new TpaRequest(from, to, kind, now, now + standingFor.toMillis());
        outgoing.put(key, made);
        return new Outcome(Optional.of(made), pushedAside.stream().findFirst());
    }

    /**
     * Asks several people at once. Whoever was already asked and has not answered is left alone, whatever
     * else this player had standing with people outside the batch is pushed aside — one asking, one change
     * of mind, as with a single person.
     */
    public synchronized Batch askAll(UUID from, java.util.Collection<UUID> targets, TpaKind kind) {
        if (from == null || targets == null || kind == null) {
            return new Batch(List.of(), List.of());
        }
        long now = clock.getAsLong();
        java.util.Set<UUID> wanted = new java.util.LinkedHashSet<>(targets);
        wanted.remove(from);
        wanted.remove(null);
        List<TpaRequest> pushedAside = clearOthers(from, wanted, now);
        List<TpaRequest> made = new ArrayList<>();
        for (UUID to : wanted) {
            Key key = new Key(from, to);
            TpaRequest held = outgoing.get(key);
            if (held != null && !held.isExpired(now)) {
                continue;
            }
            TpaRequest request = new TpaRequest(from, to, kind, now, now + standingFor.toMillis());
            outgoing.put(key, request);
            made.add(request);
        }
        return new Batch(List.copyOf(made), List.copyOf(pushedAside));
    }

    /** Removes what {@code from} has standing with anybody outside {@code keep}; returns what was live. */
    private List<TpaRequest> clearOthers(UUID from, java.util.Set<UUID> keep, long now) {
        List<TpaRequest> pushedAside = new ArrayList<>();
        for (TpaRequest request : List.copyOf(outgoing.values())) {
            if (!request.from().equals(from) || keep.contains(request.to())) {
                continue;
            }
            outgoing.remove(new Key(request.from(), request.to()), request);
            if (!request.isExpired(now)) {
                pushedAside.add(request);
            }
        }
        return pushedAside;
    }

    // ------------------------------------------------------------------------ looking

    /** What this player has asked, if it still stands — the newest, when they asked several at once. */
    public Optional<TpaRequest> from(UUID asker) {
        return allFrom(asker).stream().findFirst();
    }

    /** Everything this player has asked that still stands, newest first. */
    public List<TpaRequest> allFrom(UUID asker) {
        if (asker == null) {
            return List.of();
        }
        long now = clock.getAsLong();
        return outgoing.values().stream()
                .filter(request -> request.from().equals(asker))
                .filter(request -> !request.isExpired(now))
                .sorted(Comparator.comparingLong(TpaRequest::madeAt).reversed()
                        .thenComparing(request -> request.to().toString()))
                .toList();
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
        if (asker == null || asked == null) {
            return false;
        }
        TpaRequest held = outgoing.get(new Key(asker, asked));
        return held != null && !held.isExpired(clock.getAsLong());
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
                ? Optional.ofNullable(outgoing.get(new Key(from, asked)))
                        .filter(request -> !request.isExpired(clock.getAsLong())).orElse(null)
                : to(asked).stream().findFirst().orElse(null);
        if (wanted == null) {
            return Optional.empty();
        }
        // Only if it is still the one we looked at: between the read and here, they may have withdrawn
        // it and asked somebody else.
        return outgoing.remove(new Key(wanted.from(), wanted.to()), wanted) ? Optional.of(wanted) : Optional.empty();
    }

    /** The asker taking their own request back — the newest, when they asked several. */
    public Optional<TpaRequest> withdraw(UUID asker) {
        return from(asker).filter(request -> outgoing.remove(new Key(asker, request.to()), request));
    }

    /** The asker taking back what they asked of this one person. */
    public Optional<TpaRequest> withdraw(UUID asker, UUID asked) {
        if (asker == null || asked == null) {
            return Optional.empty();
        }
        Key key = new Key(asker, asked);
        return Optional.ofNullable(outgoing.get(key))
                .filter(request -> !request.isExpired(clock.getAsLong()))
                .filter(request -> outgoing.remove(key, request));
    }

    /** The asker taking back everything they asked. */
    public List<TpaRequest> withdrawAll(UUID asker) {
        List<TpaRequest> taken = new ArrayList<>();
        for (TpaRequest request : allFrom(asker)) {
            if (outgoing.remove(new Key(asker, request.to()), request)) {
                taken.add(request);
            }
        }
        return List.copyOf(taken);
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
            if (request.isExpired(now) && outgoing.remove(new Key(request.from(), request.to()), request)) {
                lapsed.add(request);
            }
        }
        return List.copyOf(lapsed);
    }

    /**
     * Everything involving this player, removed and handed back. Called when they log out.
     *
     * <p>Both directions. Their own requests go because there is nobody left to travel; the ones asked
     * of them go because otherwise those people wait out a full minute for an answer from somebody who
     * is not there. Reported whether or not they had just expired: the other side is still standing
     * there holding a request that is now gone, and "they left" is the true and useful thing to say.
     */
    public List<TpaRequest> forget(UUID player) {
        if (player == null) {
            return List.of();
        }
        List<TpaRequest> ended = new ArrayList<>();
        for (TpaRequest request : List.copyOf(outgoing.values())) {
            boolean theirs = request.from().equals(player) || request.to().equals(player);
            if (theirs && outgoing.remove(new Key(request.from(), request.to()), request)) {
                ended.add(request);
            }
        }
        return List.copyOf(ended);
    }

    /** Forgets everybody. For a plugin being disabled. */
    public void clear() {
        outgoing.clear();
    }
}
