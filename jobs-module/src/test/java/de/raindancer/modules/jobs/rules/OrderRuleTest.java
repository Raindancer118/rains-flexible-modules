package de.raindancer.modules.jobs.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.model.Work;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRuleTest {

    private final OrderRule rule = new OrderRule();
    private final Money easy = Money.of(1_000);
    private final Money hardest = Money.of(100_000_000_000L);

    private static Work work(String id, double hardness) {
        return new Work(id, id, "paper", QuestTask.KILL, ItemSelection.parse(List.of(id)), "10", 10, hardness, "");
    }

    private static Work work(String id, QuestTask task, double hardness, String value, double rate, String cap) {
        return new Work(id, id, "paper", task, ItemSelection.parse(List.of(id)), value, rate, hardness, cap);
    }

    @Test
    @DisplayName("difficulty runs from nothing up to the easy amount to all at the hardest, by orders of magnitude")
    void difficulty() {
        assertThat(rule.difficulty(Money.of(100), easy, hardest)).isZero();
        assertThat(rule.difficulty(easy, easy, hardest)).isZero();
        assertThat(rule.difficulty(Money.of(100_000_000), easy, hardest)).isCloseTo(0.625, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(rule.difficulty(hardest, easy, hardest)).isEqualTo(1.0);
        assertThat(rule.difficulty(Money.of(Long.MAX_VALUE), easy, hardest)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a hundred million is about a hundred Wardens in about half an hour")
    void hundredMillion() {
        Work wardens = new Work("warden", "Wardens", "sculk", QuestTask.KILL, ItemSelection.parse(List.of("warden")),
                "750", 6, 0.75, "");
        double d = rule.difficulty(Money.of(100_000_000), easy, hardest);
        int units = rule.units(Money.of(100_000_000), rule.perUnit(Money.of(750), d), 1.0);
        Duration time = rule.time(units, wardens.rate(), rule.pressure(d, 0.15, 750));
        assertThat(units).isBetween(80, 120);
        assertThat(time).isBetween(Duration.ofMinutes(15), Duration.ofMinutes(45));
        assertThat(wardens.says(units)).isEqualTo("Kill " + units + " Wardens");
    }

    @Test
    @DisplayName("the top is hopeless: hundreds of dragons in minutes; the bottom is an afternoon's work")
    void ends() {
        double top = 1.0;
        int dragons = rule.units(hardest, rule.perUnit(Money.of(3_000), top), 1.0);
        Duration topTime = rule.time(dragons, 2, rule.pressure(top, 0.15, 750));
        assertThat(dragons).isGreaterThan(200);
        assertThat(topTime).isLessThan(Duration.ofMinutes(30));
        assertThat(dragons / (2.0 * topTime.toMinutes() / 60)).as("times a skilled pace").isGreaterThan(300);

        int zombies = rule.units(easy, rule.perUnit(Money.of(8), 0), 1.0);
        Duration easyTime = rule.time(zombies, 150, rule.pressure(0, 0.15, 750));
        assertThat(zombies).isEqualTo(130);
        assertThat(easyTime).isBetween(Duration.ofHours(4), Duration.ofHours(7));
    }

    @Test
    @DisplayName("more asked never means less work, and the clock gets tighter for every unit")
    void monotonic() {
        Money value = Money.of(50);
        int lastUnits = 0;
        double lastPace = 0;
        for (long asked = 1_000; asked <= 100_000_000_000L; asked *= 10) {
            double d = rule.difficulty(Money.of(asked), easy, hardest);
            int units = rule.units(Money.of(asked), rule.perUnit(value, d), 1.0);
            double pace = units / (double) rule.time(units, 20, rule.pressure(d, 0.15, 750)).toMinutes();
            assertThat(units).as("units at " + asked).isGreaterThanOrEqualTo(lastUnits);
            assertThat(pace).as("pace at " + asked).isGreaterThanOrEqualTo(lastPace);
            lastUnits = units;
            lastPace = pace;
        }
    }

    @Test
    @DisplayName("counts read well; the clock is whole minutes, at least two, at most a week")
    void rounding() {
        assertThat(OrderRule.nice(7.4)).isEqualTo(7);
        assertThat(OrderRule.nice(37)).isEqualTo(35);
        assertThat(OrderRule.nice(1_234)).isEqualTo(1_200);
        assertThat(rule.time(1, 1_000, 1000)).isEqualTo(OrderRule.SHORTEST);
        assertThat(rule.time(1_000_000, 1, 0.01)).isEqualTo(OrderRule.LONGEST);
    }

    @Test
    @DisplayName("work is picked near the order's difficulty, with variety; far off, the nearest")
    void picks() {
        List<Work> works = List.of(work("zombie", 0.05), work("skeleton", 0.1), work("spider", 0.12),
                work("blaze", 0.45), work("warden", 0.8), work("dragon", 1.0));
        Set<String> easyOnes = new HashSet<>();
        Random random = new Random(3);
        for (int i = 0; i < 50; i++) {
            easyOnes.add(rule.pick(works, 0.05, random).id());
        }
        assertThat(easyOnes).containsExactlyInAnyOrder("zombie", "skeleton", "spider");
        assertThat(rule.pick(List.of(work("zombie", 0.05)), 1.0, random).id()).isEqualTo("zombie");
        assertThat(rule.pick(works, 0.95, random).id()).isIn("warden", "dragon");
    }

    @Test
    @DisplayName("several offers are different work, near the order's difficulty first, then the nearest others")
    void picksSeveral() {
        List<Work> works = List.of(work("zombie", 0.05), work("skeleton", 0.1), work("spider", 0.12),
                work("blaze", 0.45), work("warden", 0.8), work("dragon", 1.0));
        List<Work> four = rule.pickSome(works, 0.05, 4, Money.of(1_000), new Random(9));
        assertThat(four).hasSize(4).doesNotHaveDuplicates();
        assertThat(four.subList(0, 3)).extracting(Work::id).containsExactlyInAnyOrder("zombie", "skeleton", "spider");
        assertThat(four.get(3).id()).as("the next nearest").isEqualTo("blaze");
        assertThat(rule.pickSome(works, 0.5, 10, Money.of(1_000), new Random(9))).as("never more than there is").hasSize(6);
    }

    @Test
    @DisplayName("offers are of different kinds first — not four kinds of killing when there is mining and fishing too")
    void differentKinds() {
        List<Work> works = List.of(work("zombie", QuestTask.KILL, 0.05, "8", 150, ""),
                work("skeleton", QuestTask.KILL, 0.06, "8", 150, ""), work("spider", QuestTask.KILL, 0.07, "8", 150, ""),
                work("stone", QuestTask.MINE, 0.05, "1", 1200, ""), work("cod", QuestTask.FISH, 0.08, "8", 60, ""),
                work("bread", QuestTask.CRAFT, 0.04, "3", 300, "27"));
        for (int seed = 0; seed < 20; seed++) {
            List<Work> four = rule.pickSome(works, 0.05, 4, Money.of(1_000), new Random(seed));
            assertThat(four).extracting(Work::task).as("seed " + seed).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("work whose inputs can be bought is never offered where a unit would pay more than its cap")
    void capped() {
        Work beacons = work("beacon", QuestTask.CRAFT, 0.3, "300", 4, "18000");
        assertThat(rule.allowed(beacons, 0.3)).isTrue();
        assertThat(rule.allowed(beacons, 0.6)).as("300 × 100,000^0.6 is far past 18,000").isFalse();
        assertThat(rule.allowed(work("warden", QuestTask.KILL, 0.85, "750", 6, ""), 1.0)).as("no cap").isTrue();
        assertThat(rule.pickSome(List.of(beacons), 0.9, 4, Money.of(1_000), new Random(1))).isEmpty();
    }

    @Test
    @DisplayName("a chosen time picks work that fits it, and never asks less work than the amount buys")
    void chosenTime() {
        Work cod = work("cod", QuestTask.FISH, 0.1, "8", 60, "");
        double d = 0.1;
        double pace = rule.pressure(d, 0.15, 750);
        Money asked = de.raindancer.core.social.economy.Fees.amount("5000");
        int natural = rule.units(asked, rule.perUnit(de.raindancer.core.social.economy.Fees.amount("8"), d), 1.0);
        int quick = rule.unitsIn(asked, cod, d, Duration.ofMinutes(10), pace);
        int slow = rule.unitsIn(asked, cod, d, Duration.ofDays(3), pace);
        assertThat(quick).as("ten minutes is no discount").isGreaterThanOrEqualTo((int) (natural * 0.75));
        assertThat(slow).as("three days is more work for the same pay").isGreaterThan(natural);
        List<Work> works = List.of(work("stone", QuestTask.MINE, 0.1, "1", 1200, ""),
                work("debris", QuestTask.MINE, 0.15, "250", 500, ""));
        Work fitsAnHour = rule.fitting(works, d, asked, Duration.ofHours(1), pace, 1, new Random(1)).getFirst();
        Work fitsTwoMinutes = rule.fitting(works, d, asked, Duration.ofMinutes(2), pace, 1, new Random(1)).getFirst();
        assertThat(List.of(fitsAnHour.id(), fitsTwoMinutes.id())).as("different work for different time").doesNotHaveDuplicates();
    }
}
