package de.raindancer.modules.jobs.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.jobs.model.QuestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * How hard a player's quests are and what they pay. The tier grows with the player's balance — one step at
 * every doubling past the first step — so quests stay worth doing however rich somebody gets: each tier asks
 * a little more and pays rather more.
 */
public final class QuestRule implements IJobsRule {

    /** Tier 0 below one step, 1 from one step, 2 from three, 3 from seven… capped at {@code most}. */
    public int tier(Money balance, Money step, int most) {
        if (!step.isPositive() || !balance.isPositive()) {
            return 0;
        }
        double ratio = balance.minor() / (double) step.minor();
        int tier = (int) Math.floor(Math.log1p(ratio) / Math.log(2) + 1e-9);
        return Math.clamp(tier, 0, Math.max(0, most));
    }

    public int amount(int base, int tier, int harderPercent) {
        double grown = Math.max(1, base) * Math.pow(1 + Math.max(0, harderPercent) / 100.0, Math.max(0, tier));
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE / 2, Math.round(grown)));
    }

    /**
     * @param bonusPercent extra for a quest of the player's own role
     * @param scalePercent the owner's scale over all quest pay; 100 leaves it
     */
    public Money pay(Money base, int tier, int morePercent, int bonusPercent, int scalePercent) {
        if (!base.isPositive() || scalePercent <= 0) {
            return Money.ZERO;
        }
        BigDecimal factor = BigDecimal.valueOf(Math.pow(1 + Math.max(0, morePercent) / 100.0, Math.max(0, tier)))
                .multiply(BigDecimal.valueOf(1 + Math.max(0, bonusPercent) / 100.0))
                .multiply(BigDecimal.valueOf(scalePercent / 100.0));
        long paid = BigDecimal.valueOf(base.minor()).multiply(factor).setScale(0, RoundingMode.FLOOR).longValue();
        return Money.of(Math.max(1, paid));
    }

    /**
     * The quests for one player's day: {@code general} that anybody can do and {@code forRole} of their role's.
     * Yesterday's are left out while there are others to give.
     */
    public List<QuestTemplate> pick(Collection<QuestTemplate> all, String role, int general, int forRole,
                                    Collection<String> yesterday, Random random) {
        List<QuestTemplate> picked = new ArrayList<>();
        picked.addAll(some(all.stream().filter(each -> !each.forRole()).toList(), general, yesterday, random));
        if (role != null && !role.isEmpty()) {
            picked.addAll(some(all.stream().filter(each -> each.role().equalsIgnoreCase(role)).toList(), forRole,
                    yesterday, random));
        }
        return picked;
    }

    private static List<QuestTemplate> some(List<QuestTemplate> from, int how, Collection<String> yesterday,
                                            Random random) {
        List<QuestTemplate> fresh = new ArrayList<>(from.stream().filter(each -> !yesterday.contains(each.id())).toList());
        List<QuestTemplate> stale = new ArrayList<>(from.stream().filter(each -> yesterday.contains(each.id())).toList());
        Collections.shuffle(fresh, random);
        Collections.shuffle(stale, random);
        fresh.addAll(stale);
        return fresh.subList(0, Math.min(Math.max(0, how), fresh.size()));
    }

    @Override
    public String describe() {
        return "a player's quest tier from their balance, and what each tier asks and pays";
    }
}
