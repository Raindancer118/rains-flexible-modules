package de.raindancer.modules.guilds.rules;

/** The bonus for playing near guildmates: so many percent per guildmate nearby, up to a most. */
public final class TogetherRule implements IGuildsRule {

    private final int perMember;
    private final int most;

    public TogetherRule(int perMember, int most) {
        this.perMember = Math.max(0, perMember);
        this.most = Math.max(0, most);
    }

    @Override
    public String describe() {
        return "+" + perMember + "% experience per guildmate nearby, at most +" + most + "%";
    }

    public int bonusPercent(int guildmatesNearby) {
        return (int) Math.min(most, (long) perMember * Math.max(0, guildmatesNearby));
    }

    /** {@code base} with every bonus percent added, never below zero, never past a long. */
    public static long withBonus(long base, int percent) {
        if (base <= 0) {
            return 0;
        }
        long factor = 100L + Math.max(0, percent);
        return base > Long.MAX_VALUE / factor ? Long.MAX_VALUE / 100 : base * factor / 100;
    }
}
