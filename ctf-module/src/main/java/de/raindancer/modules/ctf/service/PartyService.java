package de.raindancer.modules.ctf.service;

import de.raindancer.modules.ctf.CtfSettings;
import de.raindancer.modules.ctf.model.Party;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Friends who want to play on the same side. Invites last two minutes. A party lives only while its members are
 * here — leaving the server leaves the party.
 */
public final class PartyService implements ICtfService {

    public static final long INVITE_MILLIS = 120_000;
    public static final int MOST_MEMBERS = 8;

    /** What an invite or an answer came to; the message keys say it. */
    public enum Outcome {
        INVITED("ctf.party.invited"), ALREADY_IN("ctf.party.already-in"), FULL("ctf.party.full"),
        NOT_LEADER("ctf.party.not-leader"), JOINED("ctf.party.joined"), NO_INVITE("ctf.party.no-invite"),
        SELF("ctf.party.self");

        private final String key;

        Outcome(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    private record Invite(UUID leader, long until) {
    }

    private final LongSupplier clock;
    private final List<Party> parties = new ArrayList<>();
    private final Map<UUID, Invite> invites = new ConcurrentHashMap<>();

    public PartyService(LongSupplier clock) {
        this.clock = clock;
    }

    @Override
    public void settings(CtfSettings settings) {
        // Nothing read: a party is between players, not a server's rule.
    }

    public synchronized Optional<Party> of(UUID player) {
        return parties.stream().filter(party -> party.has(player)).findFirst();
    }

    public synchronized List<Set<UUID>> all() {
        return parties.stream().map(Party::members).toList();
    }

    public synchronized Outcome invite(UUID from, UUID target) {
        if (from.equals(target)) {
            return Outcome.SELF;
        }
        Optional<Party> mine = of(from);
        if (mine.isPresent() && !mine.get().leader().equals(from)) {
            return Outcome.NOT_LEADER;
        }
        if (mine.isPresent() && mine.get().size() >= MOST_MEMBERS) {
            return Outcome.FULL;
        }
        if (of(target).isPresent() && mine.map(party -> party.has(target)).orElse(false)) {
            return Outcome.ALREADY_IN;
        }
        invites.put(target, new Invite(from, clock.getAsLong() + INVITE_MILLIS));
        return Outcome.INVITED;
    }

    /** Accepts the invite somebody has; leaves any party they were in. */
    public synchronized Outcome accept(UUID player) {
        Invite invite = invites.remove(player);
        if (invite == null || invite.until() < clock.getAsLong()) {
            return Outcome.NO_INVITE;
        }
        leave(player);
        Optional<Party> theirs = of(invite.leader());
        if (theirs.isPresent() && theirs.get().size() >= MOST_MEMBERS) {
            return Outcome.FULL;
        }
        if (theirs.isPresent()) {
            parties.set(parties.indexOf(theirs.get()), theirs.get().with(player));
        } else {
            parties.add(new Party(invite.leader(), Set.of(player)));
        }
        return Outcome.JOINED;
    }

    public synchronized Optional<UUID> invitedBy(UUID player) {
        Invite invite = invites.get(player);
        return invite == null || invite.until() < clock.getAsLong() ? Optional.empty() : Optional.of(invite.leader());
    }

    /** @return the party they were in, before they left it */
    public synchronized Optional<Party> leave(UUID player) {
        Optional<Party> was = of(player);
        was.ifPresent(party -> {
            int at = parties.indexOf(party);
            Optional<Party> rest = party.without(player).filter(left -> left.size() > 1);
            if (rest.isPresent()) {
                parties.set(at, rest.get());
            } else {
                parties.remove(at);
            }
        });
        return was;
    }

    /** The leader sends somebody out. */
    public synchronized boolean kick(UUID leader, UUID player) {
        Optional<Party> party = of(leader);
        if (party.isEmpty() || !party.get().leader().equals(leader) || !party.get().has(player) || leader.equals(player)) {
            return false;
        }
        leave(player);
        return true;
    }

    public void forget(UUID player) {
        invites.remove(player);
        leave(player);
    }
}
