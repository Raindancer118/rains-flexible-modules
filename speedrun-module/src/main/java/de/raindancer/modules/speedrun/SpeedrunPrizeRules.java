package de.raindancer.modules.speedrun;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PrizePot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who is paid what out of a run's entry fees. Nothing is paid that was not taken: the house cut is rounded
 * down, every share is rounded down, and the rounding dust goes to first place, so what is paid out is the
 * pot minus the cut to the unit.
 */
public final class SpeedrunPrizeRules {

    private static final PrizePot POT = new PrizePot();

    /** The part of the pot the server keeps; Core's {@link PrizePot} works it out. */
    public Money houseCut(Money pot, int percent) {
        return POT.houseCut(pot, percent);
    }

    /**
     * @param places first place first; each place is the racers who share it, evenly
     * @param split  weights per place, "100" or "60,30,10"; unreadable falls back to winner takes all
     */
    public Map<UUID, Money> payouts(Money pot, int housePercent, String split, List<List<UUID>> places) {
        return POT.payouts(pot, housePercent, split, places);
    }

    /** The winners first, then everybody else; no places at all when nobody won. */
    public List<List<UUID>> places(Collection<UUID> racers, Collection<UUID> winners) {
        List<UUID> first = new ArrayList<>(new LinkedHashSet<>(winners));
        first.retainAll(racers);
        if (first.isEmpty()) {
            return List.of();
        }
        List<UUID> rest = new ArrayList<>(new LinkedHashSet<>(racers));
        rest.removeAll(first);
        return rest.isEmpty() ? List.of(first) : List.of(first, rest);
    }

    /**
     * Who won a run that ended for {@code reason}.
     *
     * @param modeResult in a game with sides, the racers its own results name as winners; empty when the
     *                   game says nothing, which is then nobody — never a guess
     * @param plainRace  whether no game mode was played
     * @return everybody who raced for a plain race that reached its goal, the mode's winners for a game, and
     *         nobody for a run that was reset or ended any other way
     */
    public Set<UUID> winners(Set<UUID> racers, boolean plainRace, Optional<Set<UUID>> modeResult, String reason) {
        if (reason == null || reason.equals("admin-reset")) {
            return Set.of();
        }
        if (!plainRace) {
            return modeResult.orElse(Set.of());
        }
        return reason.startsWith("advancement:") ? Set.copyOf(racers) : Set.of();
    }

}
