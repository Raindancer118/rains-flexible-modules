package de.raindancer.modules.manhunt.stats;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("ratings and balancing")
class RatingTest {

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }

    @Test
    @DisplayName("two equal sides of equal size are a coin toss")
    void evenIsHalf() {
        assertThat(Rating.runnersExpected(List.of(1000.0), List.of(1000.0))).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("more Hunters make the Runners' chances worse, a better Runner makes them better")
    void sizeAndSkillCount() {
        double alone = Rating.runnersExpected(List.of(1000.0), List.of(1000.0, 1000.0, 1000.0, 1000.0));
        double strong = Rating.runnersExpected(List.of(1400.0), List.of(1000.0, 1000.0, 1000.0, 1000.0));

        assertThat(alone).isLessThan(0.5);
        assertThat(strong).isGreaterThan(alone);
    }

    @Test
    @DisplayName("winning against the odds earns more than winning as the favourite, and the sides trade exactly")
    void update() {
        Map<UUID, Double> runners = Map.of(id("r"), 1000.0);
        Map<UUID, Double> hunters = new LinkedHashMap<>();
        hunters.put(id("a"), 1000.0);
        hunters.put(id("b"), 1000.0);

        Map<UUID, Double> after = Rating.afterHunt(runners, hunters, true);

        double gained = after.get(id("r")) - 1000.0;
        assertThat(gained).isGreaterThan(Rating.K / 2);
        assertThat(after.get(id("a"))).isCloseTo(1000.0 - gained, within(1e-9));

        Map<UUID, Double> lost = Rating.afterHunt(runners, hunters, false);
        assertThat(1000.0 - lost.get(id("r"))).isLessThan(Rating.K / 2);
    }

    @Test
    @DisplayName("auto-balance picks the Runners that make the hunt closest to even")
    void balance() {
        Map<UUID, Double> ratings = new LinkedHashMap<>();
        ratings.put(id("pro"), 1600.0);
        for (int i = 0; i < 5; i++) {
            ratings.put(id("p" + i), 1000.0);
        }

        BalancePlanner.Plan plan = BalancePlanner.balance(ratings, 0);

        assertThat(plan.runners()).contains(id("pro"));
        assertThat(plan.runners().size() + plan.hunters().size()).isEqualTo(6);
        // No other split of one or two Runners is closer to even.
        double best = Math.abs(plan.runnersExpected() - 0.5);
        List<UUID> all = List.copyOf(ratings.keySet());
        for (UUID a : all) {
            assertThat(best).isLessThanOrEqualTo(distance(ratings, Set.of(a)) + 1e-9);
            for (UUID b : all) {
                if (!a.equals(b)) {
                    assertThat(best).isLessThanOrEqualTo(distance(ratings, Set.of(a, b)) + 1e-9);
                }
            }
        }
    }

    private static double distance(Map<UUID, Double> ratings, Set<UUID> runners) {
        List<Double> r = runners.stream().map(ratings::get).toList();
        List<Double> h = ratings.keySet().stream().filter(id -> !runners.contains(id)).map(ratings::get).toList();
        return Math.abs(Rating.runnersExpected(r, h) - 0.5);
    }

    @Test
    @DisplayName("everybody new is balanced by numbers alone, and a cap on Runners is kept")
    void balanceByNumbers() {
        Map<UUID, Double> ratings = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            ratings.put(id("p" + i), Rating.START);
        }

        BalancePlanner.Plan free = BalancePlanner.balance(ratings, 0);
        BalancePlanner.Plan capped = BalancePlanner.balance(ratings, 1);

        assertThat(free.runners()).hasSizeBetween(1, 4);
        assertThat(capped.runners()).hasSize(1);
        assertThat(BalancePlanner.balance(ratings, 0).runners()).as("the same answer twice").isEqualTo(free.runners());
    }

    @Test
    @DisplayName("a crowd too big to try every split still gets a sensible answer, quickly")
    void bigCrowd() {
        Map<UUID, Double> ratings = new LinkedHashMap<>();
        Random random = new Random(7);
        for (int i = 0; i < 60; i++) {
            ratings.put(id("p" + i), 800 + random.nextInt(800) * 1.0);
        }

        long start = System.nanoTime();
        BalancePlanner.Plan plan = BalancePlanner.balance(ratings, 0);

        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2000);
        assertThat(Math.abs(plan.runnersExpected() - 0.5)).isLessThan(0.1);
    }

    @Test
    @DisplayName("fewer than two players cannot be split")
    void tooFew() {
        assertThat(BalancePlanner.balance(Map.of(id("x"), 1000.0), 0).runners()).isEmpty();
    }

    @Test
    @DisplayName("random Runners are that many, all different, drawn from who is there")
    void random() {
        List<UUID> present = List.of(id("a"), id("b"), id("c"), id("d"));

        Set<UUID> picked = BalancePlanner.random(present, 2, new Random(1));

        assertThat(picked).hasSize(2).isSubsetOf(present);
        assertThat(BalancePlanner.random(present, 9, new Random(1))).as("never everybody").hasSize(3);
    }
}
