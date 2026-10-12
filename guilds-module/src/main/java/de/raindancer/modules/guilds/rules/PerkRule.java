package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.Perk;
import de.raindancer.modules.guilds.model.Standing;

import java.time.Duration;

/**
 * Perk points: where they come from, what they may buy, and what what was bought does.
 *
 * <p>Points come from levels ever gained (a prestige keeps them), from each prestige, and from each weekly crown.
 * A rank costs one point and needs the guild to be at its level <em>now</em> — so a guild that prestiged keeps
 * its perks but buys no new high ranks until it has climbed back.
 */
public final class PerkRule implements IGuildsRule {

    private final int pointsPerPrestige;
    private final int pointsPerCrown;
    private final Duration respecEvery;

    public PerkRule(int pointsPerPrestige, int pointsPerCrown, Duration respecEvery) {
        this.pointsPerPrestige = Math.max(0, pointsPerPrestige);
        this.pointsPerCrown = Math.max(0, pointsPerCrown);
        this.respecEvery = respecEvery == null || respecEvery.isNegative() ? Duration.ZERO : respecEvery;
    }

    @Override
    public String describe() {
        return "a perk rank costs a point and needs its level; points come from levels, prestige ("
                + pointsPerPrestige + ") and crowns (" + pointsPerCrown + ")";
    }

    public int earned(Standing standing) {
        long total = (long) standing.levelPoints() + (long) standing.prestige() * pointsPerPrestige
                + (long) standing.crowns() * pointsPerCrown;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    public int unspent(Standing standing) {
        return Math.max(0, earned(standing) - standing.spent());
    }

    /** Whether the next rank of {@code perk} may be bought by a guild at {@code level}. */
    public GuildOutcome mayRaise(Standing standing, Perk perk, int level) {
        int next = standing.rank(perk) + 1;
        if (next > perk.maxRank()) {
            return GuildOutcome.PERK_MAXED;
        }
        if (level < perk.levelFor(next)) {
            return GuildOutcome.LEVEL_TOO_LOW;
        }
        return unspent(standing) > 0 ? GuildOutcome.SUCCESS : GuildOutcome.NO_PERK_POINTS;
    }

    public GuildOutcome mayRespec(Standing standing, long now) {
        if (standing.spent() == 0) {
            return GuildOutcome.NOTHING_TO_RESPEC;
        }
        boolean waited = standing.respecAt() <= 0 || now - standing.respecAt() >= respecEvery.toMillis();
        return waited ? GuildOutcome.SUCCESS : GuildOutcome.ON_COOLDOWN;
    }

    public long respecAgainAt(Standing standing) {
        return standing.respecAt() <= 0 ? 0 : standing.respecAt() + respecEvery.toMillis();
    }

    // ------------------------------------------------------------------ what was bought does

    public static int vaultSlots(Standing standing) {
        return standing.rank(Perk.VAULT) * 9;
    }

    /** Potion amplifier, or -1 for none. */
    public static int hasteAmplifier(Standing standing) {
        return standing.rank(Perk.HASTE) - 1;
    }

    public static int luckAmplifier(Standing standing) {
        return standing.rank(Perk.LUCK) - 1;
    }

    public static int vanillaXpPercent(Standing standing) {
        return 10 * standing.rank(Perk.SCHOLAR);
    }

    public static int extraMembers(Standing standing) {
        return 2 * standing.rank(Perk.RECRUITER);
    }

    public static int extraAllies(Standing standing) {
        return standing.rank(Perk.DIPLOMAT);
    }

    /** The guild-experience bonus from perks and prestige, in percent. */
    public static int guildXpPercent(Standing standing, int percentPerPrestige) {
        return 5 * standing.rank(Perk.PROSPEROUS) + Math.max(0, percentPerPrestige) * standing.prestige();
    }

    /** What is left of the home's warm-up and wait, in percent: 100, 50, then 0. */
    public static int homeTravelPercent(Standing standing) {
        return Math.max(0, 100 - 50 * standing.rank(Perk.PATHFINDER));
    }

    /** "★", "★2", … for a guild that prestiged; empty for one that did not. */
    public static String stars(int prestige) {
        return prestige <= 0 ? "" : prestige == 1 ? "★" : "★" + prestige;
    }
}
