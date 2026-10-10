package de.raindancer.modules.jobs.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.jobs.model.Work;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Turns an amount somebody wants to earn into work. The more they ask for, the harder the work picked, the
 * more each unit is worth (so the count stays something a person can read: a hundred Wardens, not a million)
 * and the less time they get. Somewhere past a million it stops being comfortable; at the top it is a joke —
 * three hundred Ender Dragons in a quarter of an hour.
 *
 * <p>Pressure is how much faster than a skilled player's pace the clock asks for: a fraction at the bottom,
 * hundreds of times at the top.
 */
public final class OrderRule implements IJobsRule {

    /** How much more a unit is worth in the hardest order than in the easiest. */
    public static final double UNIT_GROWTH = 100_000;
    /** How hard the hardest order may be off the order's own difficulty and still be picked. */
    public static final double WINDOW = 0.2;
    public static final Duration SHORTEST = Duration.ofMinutes(2);
    public static final Duration LONGEST = Duration.ofDays(7);

    /** 0 up to {@code easy}, 1 at {@code hardest}, evenly by orders of magnitude between. */
    public double difficulty(Money asked, Money easy, Money hardest) {
        if (!asked.isPositive() || !easy.isPositive() || !hardest.isMoreThan(easy)) {
            return 0;
        }
        double d = Math.log(asked.minor() / (double) easy.minor()) / Math.log(hardest.minor() / (double) easy.minor());
        return Math.clamp(d, 0.0, 1.0);
    }

    /** How many times a skilled player's pace the clock asks for. */
    public double pressure(double difficulty, double easiest, double hardest) {
        return easiest * Math.pow(hardest / easiest, Math.clamp(difficulty, 0.0, 1.0));
    }

    /** What one unit of this work is worth in an order of this difficulty. */
    public Money perUnit(Money value, double difficulty) {
        BigDecimal grown = BigDecimal.valueOf(value.minor()).multiply(BigDecimal.valueOf(Math.pow(UNIT_GROWTH,
                Math.clamp(difficulty, 0.0, 1.0))));
        return Money.of(Math.max(1, grown.setScale(0, RoundingMode.HALF_UP).longValue()));
    }

    /** How many units {@code asked} buys at {@code perUnit}, moved by {@code spread} and rounded to read well. */
    public int units(Money asked, Money perUnit, double spread) {
        double exact = asked.minor() / (double) Math.max(1, perUnit.minor()) * spread;
        return nice(Math.max(1, exact));
    }

    /** 1–20 as they are; then two significant figures, ending in 0 or 5: 37 → 35, 1,234 → 1,200. */
    static int nice(double value) {
        if (value <= 20) {
            return (int) Math.max(1, Math.round(value));
        }
        double magnitude = Math.pow(10, Math.floor(Math.log10(value)) - 1);
        double step = magnitude * (value < 100 ? 5 : 1);
        return (int) Math.min(Integer.MAX_VALUE / 2, Math.max(step, Math.round(value / step) * step));
    }

    /** The time to do it in: the units at the pressured pace, rounded to whole minutes, five or fifteen. */
    public Duration time(int units, double rate, double pressure) {
        double minutes = units / (Math.max(0.01, rate) * Math.max(0.0001, pressure)) * 60;
        long rounded = minutes < 10 ? Math.round(minutes) : minutes < 120 ? Math.round(minutes / 5) * 5
                : Math.round(minutes / 15) * 15;
        long clamped = Math.clamp(rounded, SHORTEST.toMinutes(), LONGEST.toMinutes());
        return Duration.ofMinutes(clamped);
    }

    /** Work about as hard as the order; the nearest there is when nothing is close enough. */
    public Work pick(List<Work> works, double difficulty, Random random) {
        if (works.isEmpty()) {
            throw new IllegalArgumentException("no work to pick from");
        }
        List<Work> close = works.stream().filter(each -> Math.abs(each.hardness() - difficulty) <= WINDOW).toList();
        if (close.isEmpty()) {
            return works.stream().min(Comparator.comparingDouble(each -> Math.abs(each.hardness() - difficulty)))
                    .orElseThrow();
        }
        return close.get(random.nextInt(close.size()));
    }

    /** Whether this work may be offered at this difficulty: within its cap, when it has one. */
    public boolean allowed(Work work, double difficulty) {
        if (work.cap().isEmpty()) {
            return true;
        }
        Money cap = de.raindancer.core.social.economy.Fees.amount(work.cap());
        return !perUnit(de.raindancer.core.social.economy.Fees.amount(work.value()), difficulty).isMoreThan(cap);
    }

    /**
     * {@code how} different works for one order: about as hard, of different kinds first — mining, fishing,
     * crafting rather than four ways of killing — then more about as hard, then the nearest others.
     */
    public List<Work> pickSome(List<Work> works, double difficulty, int how, Money asked, Random random) {
        List<Work> usable = works.stream().filter(each -> allowed(each, difficulty)).toList();
        List<Work> close = new java.util.ArrayList<>(usable.stream()
                .filter(each -> Math.abs(each.hardness() - difficulty) <= WINDOW).toList());
        java.util.Collections.shuffle(close, random);
        List<Work> ordered = new java.util.ArrayList<>(close);
        usable.stream().filter(each -> !close.contains(each))
                .sorted(Comparator.comparingDouble(each -> Math.abs(each.hardness() - difficulty)))
                .forEach(ordered::add);
        return kindsFirst(ordered, close.size(), how);
    }

    /** From an order of preference, one of each kind among the first {@code preferred}, then the rest in order. */
    private static List<Work> kindsFirst(List<Work> ordered, int preferred, int how) {
        List<Work> picked = new java.util.ArrayList<>();
        java.util.Set<de.raindancer.modules.jobs.model.QuestTask> kinds = java.util.EnumSet.noneOf(
                de.raindancer.modules.jobs.model.QuestTask.class);
        for (Work each : ordered.subList(0, Math.min(preferred, ordered.size()))) {
            if (picked.size() < how && kinds.add(each.task())) {
                picked.add(each);
            }
        }
        for (Work each : ordered) {
            if (picked.size() >= how) {
                break;
            }
            if (!picked.contains(each)) {
                picked.add(each);
            }
        }
        return picked;
    }

    /**
     * How many units of {@code work} an order for {@code asked} asks for in a chosen time: what the time holds at
     * the order's pace, but never under what the amount buys — a short time is no discount, a long one more work.
     */
    public int unitsIn(Money asked, Work work, double difficulty, Duration time, double pace) {
        double needed = asked.minor() / (double) Math.max(1, perUnit(de.raindancer.core.social.economy.Fees.amount(
                work.value()), difficulty).minor());
        double held = work.rate() * time.toMinutes() / 60.0 * pace;
        return nice(Math.max(1, Math.max(needed * 0.8, held)));
    }

    /**
     * Works whose natural time for this amount is closest to the time chosen — what the amount buys at the order's
     * pace — of different kinds first.
     */
    public List<Work> fitting(List<Work> works, double difficulty, Money asked, Duration time, double pace, int how,
                              Random random) {
        double wanted = Math.max(1, time.toMinutes()) / 60.0;
        List<Work> usable = works.stream().filter(each -> allowed(each, difficulty)).toList();
        List<Work> near = usable.stream().filter(each -> Math.abs(each.hardness() - difficulty) <= WINDOW * 1.5).toList();
        List<Work> pool = near.isEmpty() ? usable : near;
        java.util.Map<Work, Double> score = new java.util.HashMap<>();
        for (Work each : pool) {
            double needed = asked.minor() / (double) Math.max(1, perUnit(de.raindancer.core.social.economy.Fees.amount(
                    each.value()), difficulty).minor());
            double natural = needed / (each.rate() * Math.max(0.0001, pace));
            score.put(each, Math.abs(Math.log(Math.max(1e-6, natural) / wanted)) + random.nextDouble() * 0.3);
        }
        List<Work> ordered = pool.stream().sorted(Comparator.comparingDouble(score::get)).toList();
        return kindsFirst(ordered, Math.min(ordered.size(), how * 3), how);
    }

    @Override
    public String describe() {
        return "what work an asked-for amount becomes: how hard, how many and how long";
    }
}
