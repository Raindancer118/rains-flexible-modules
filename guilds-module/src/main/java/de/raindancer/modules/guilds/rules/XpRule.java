package de.raindancer.modules.guilds.rules;

/**
 * How much of what a member earns reaches the guild.
 *
 * <p>Capped per member per hour, so one player in a mob farm cannot carry a guild to the top alone, and scaled by
 * how many are in the guild: a big guild earns more in total but each member's part counts a little less, which
 * keeps a guild of three in the race.
 */
public final class XpRule implements IGuildsRule {

    private final long hourlyCap;
    private final double sizeDamping;

    /** @param sizeDamping 0 for none; 0.5 halves what a member of a guild of… roughly e² members earns */
    public XpRule(long hourlyCap, double sizeDamping) {
        this.hourlyCap = hourlyCap <= 0 ? Long.MAX_VALUE : hourlyCap;
        this.sizeDamping = Math.max(0, Math.min(1, sizeDamping));
    }

    @Override
    public String describe() {
        return "a member earns their guild at most " + hourlyCap + " experience an hour, a little less per head "
                + "the bigger the guild";
    }

    /** What actually counts of {@code earned}, given what this member already earned this hour. */
    public long award(long earned, long earnedThisHour, int guildSize) {
        if (earned <= 0) {
            return 0;
        }
        double scaled = earned / (1.0 + sizeDamping * Math.log(Math.max(1, guildSize)));
        long counted = Math.max(1, Math.round(scaled));
        long room = Math.max(0, hourlyCap - Math.max(0, earnedThisHour));
        return Math.min(counted, room);
    }
}
