package de.raindancer.modules.hungergames.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.social.economy.PrizePot;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.model.Winner;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who is paid what out of a round's entry fees. Nothing is paid that was not taken: the house cut is rounded
 * down, every share is rounded down, and the rounding dust goes to first place, so what is paid out is the
 * pot minus the cut to the unit.
 */
public final class PrizeRules implements IHungerGamesRule {

    private static final PrizePot POT = new PrizePot();

    /** The part of the pot the server keeps; Core's {@link PrizePot} works it out. */
    public Money houseCut(Money pot, int percent) {
        return POT.houseCut(pot, percent);
    }

    /**
     * @param places first place first; each place is the players who share it (a team, or one tribute)
     * @param split  weights per place, "100" or "60,30,10"; unreadable falls back to winner takes all
     * @return what each player is paid, in place order
     */
    public Map<UUID, Money> payouts(Money pot, int housePercent, String split, List<List<UUID>> places) {
        return POT.payouts(pot, housePercent, split, places);
    }

    /**
     * The order the round ended in: the winner first, then whoever was out last, a team counting as out when
     * its last member fell.
     *
     * @param eliminated everybody out, in the order they fell
     */
    public List<List<UUID>> places(Collection<Participant> participants, List<UUID> eliminated, Winner winner) {
        List<List<UUID>> places = new ArrayList<>();
        List<UUID> first = switch (winner) {
            case Winner.Solo solo -> List.of(solo.uuid());
            case Winner.Team team -> participants.stream().map(Participant::uuid)
                    .filter(team.members()::contains).toList();
            case Winner.None none -> List.<UUID>of();
        };
        if (first.isEmpty()) {
            return places;
        }
        places.add(first);
        Map<Object, List<UUID>> units = new LinkedHashMap<>();
        for (Participant each : participants) {
            if (first.contains(each.uuid())) {
                continue;
            }
            Optional<TeamId> team = each.teamId();
            units.computeIfAbsent(team.<Object>map(id -> id).orElse(each.uuid()), key -> new ArrayList<>())
                    .add(each.uuid());
        }
        units.values().stream()
                .sorted((x, y) -> Integer.compare(lastOut(y, eliminated), lastOut(x, eliminated)))
                .forEach(places::add);
        return places;
    }

    private static int lastOut(List<UUID> unit, List<UUID> eliminated) {
        int last = -1;
        for (UUID member : unit) {
            last = Math.max(last, eliminated.lastIndexOf(member));
        }
        return last;
    }


    @Override
    public String describe() {
        return "how the pot of entry fees is cut and shared by place";
    }
}
