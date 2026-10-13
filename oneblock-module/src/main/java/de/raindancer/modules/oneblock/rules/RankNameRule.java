package de.raindancer.modules.oneblock.rules;

/** BentoBox's rank numbers, as the message key of the name players know. In-between values (custom ranks) round down. */
public final class RankNameRule implements IOneBlockRule {

    public String keyFor(int rank) {
        if (rank >= 1000) {
            return "oneblock.sidebar.rank.owner";
        }
        if (rank >= 900) {
            return "oneblock.sidebar.rank.sub-owner";
        }
        if (rank >= 500) {
            return "oneblock.sidebar.rank.member";
        }
        if (rank >= 400) {
            return "oneblock.sidebar.rank.trusted";
        }
        if (rank >= 200) {
            return "oneblock.sidebar.rank.coop";
        }
        return rank >= 0 ? "oneblock.sidebar.rank.visitor" : "oneblock.sidebar.rank.banned";
    }

    @Override
    public String describe() {
        return "what an island rank is called";
    }
}
