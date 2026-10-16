package de.raindancer.modules.guilds.rules;

/**
 * How much of what a member earns reaches the guild: scaled by how many are in the guild, so a big guild earns more
 * in total but each member's part counts a little less, which keeps a guild of three in the race.
 */
public final class XpRule implements IGuildsRule {

    private final double sizeDamping;

    /** @param sizeDamping 0 for none; 0.5 halves what a member of a guild of… roughly e² members earns */
    public XpRule(double sizeDamping) {
        this.sizeDamping = Math.max(0, Math.min(1, sizeDamping));
    }

    @Override
    public String describe() {
        return "a member's experience counts a little less per head the bigger the guild";
    }

    /** What actually counts of {@code earned}. */
    public long award(long earned, int guildSize) {
        if (earned <= 0) {
            return 0;
        }
        double scaled = earned / (1.0 + sizeDamping * Math.log(Math.max(1, guildSize)));
        return Math.max(1, Math.round(scaled));
    }
}
