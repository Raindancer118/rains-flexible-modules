package de.raindancer.modules.ranks.rules;

import de.raindancer.modules.ranks.RanksSettings;
import de.raindancer.modules.ranks.model.Holder;
import de.raindancer.modules.ranks.model.Place;
import de.raindancer.modules.ranks.model.Rank;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Which rank, if any, goes in front of somebody's name in one place. The server's switch comes first; the
 * player's own only counts while players may choose.
 */
public final class BadgeRule implements IRanksRule {

    /** The rank they wear: the one they picked if they still hold it, else their most senior. */
    public Optional<Rank> worn(Holder holder, Map<String, Rank> known, boolean playersChoose) {
        if (holder == null || holder.ranks().isEmpty()) {
            return Optional.empty();
        }
        if (playersChoose) {
            if (holder.worn().equals(Holder.HIDDEN)) {
                return Optional.empty();
            }
            if (holder.ranks().contains(holder.worn()) && known.containsKey(holder.worn())) {
                return Optional.of(known.get(holder.worn()));
            }
        }
        return holder.ranks().stream().map(known::get).filter(rank -> rank != null)
                .max(Comparator.comparingInt(Rank::weight).thenComparing(Rank::id, Comparator.reverseOrder()));
    }

    public Optional<Rank> shown(Holder holder, Map<String, Rank> known, RanksSettings settings, Place place) {
        boolean serverShows = switch (place) {
            case CHAT -> settings.chat();
            case NAMETAG -> settings.nametag();
            case TABLIST -> settings.tablist();
        };
        if (!serverShows || holder == null) {
            return Optional.empty();
        }
        if (settings.playersChoose() && !holder.shows(place)) {
            return Optional.empty();
        }
        return worn(holder, known, settings.playersChoose());
    }

    /** For the tablist's order: 0 for nobody, else the worn rank's weight, kept within what the tablist takes. */
    public int sortWeight(Holder holder, Map<String, Rank> known, RanksSettings settings) {
        if (!settings.sortTablist()) {
            return 0;
        }
        return worn(holder, known, settings.playersChoose()).map(rank -> Math.clamp(rank.weight(), 0, 99)).orElse(0);
    }

    @Override
    public String describe() {
        return "which rank shows in front of a name, where";
    }
}
