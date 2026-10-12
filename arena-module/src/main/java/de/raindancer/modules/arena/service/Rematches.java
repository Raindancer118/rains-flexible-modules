package de.raindancer.modules.arena.service;

import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.rules.PairingRule;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who just fought whom, for a minute, so they can have the same fight again: same sides, kit, rounds and stake. It
 * starts once everybody in it has asked. No server in here — the clock is handed in.
 */
public final class Rematches {

    public static final long LIFETIME_MS = 60_000;

    public enum Kind { NONE, EXPIRED, WAITING, READY }

    public record Answer(Kind kind, PairingRule.Group group, Set<UUID> missing) {
    }

    private record Offer(PairingRule.Group group, long until, Set<UUID> asked) {
    }

    private final Map<UUID, Offer> byPlayer = new HashMap<>();

    /** Whether a fight like this gets offered again at all. */
    public static boolean offered(MatchSpec spec, int players) {
        return spec.mode() != Mode.WAVES && players >= 2;
    }

    public synchronized void offer(List<Set<UUID>> sides, MatchSpec spec, long now) {
        Offer offer = new Offer(new PairingRule.Group(spec, List.copyOf(sides)), now + LIFETIME_MS, new HashSet<>());
        offer.group().players().forEach(player -> byPlayer.put(player, offer));
    }

    public synchronized Answer ask(UUID player, long now) {
        Offer offer = byPlayer.get(player);
        if (offer == null) {
            return new Answer(Kind.NONE, null, Set.of());
        }
        if (now > offer.until()) {
            drop(offer);
            return new Answer(Kind.EXPIRED, offer.group(), Set.of());
        }
        offer.asked().add(player);
        Set<UUID> missing = new HashSet<>(offer.group().players());
        missing.removeAll(offer.asked());
        if (missing.isEmpty()) {
            drop(offer);
            return new Answer(Kind.READY, offer.group(), Set.of());
        }
        return new Answer(Kind.WAITING, offer.group(), Set.copyOf(missing));
    }

    /** The fight this player could have again, while it can. */
    public synchronized Optional<PairingRule.Group> offeredTo(UUID player, long now) {
        Offer offer = byPlayer.get(player);
        return offer == null || now > offer.until() ? Optional.empty() : Optional.of(offer.group());
    }

    /** Somebody left: nobody in their offer can have it now. @return the others who were in it */
    public synchronized Set<UUID> forget(UUID player) {
        Offer offer = byPlayer.get(player);
        if (offer == null) {
            return Set.of();
        }
        drop(offer);
        Set<UUID> others = new HashSet<>(offer.group().players());
        others.remove(player);
        return others;
    }

    private void drop(Offer offer) {
        byPlayer.values().removeIf(each -> each == offer);
    }
}
