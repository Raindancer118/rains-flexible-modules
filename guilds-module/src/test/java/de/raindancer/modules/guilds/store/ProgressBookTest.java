package de.raindancer.modules.guilds.store;

import de.raindancer.modules.guilds.model.Activity;
import de.raindancer.modules.guilds.model.Boost;
import de.raindancer.modules.guilds.model.Contract;
import de.raindancer.modules.guilds.model.Fixtures;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.Perk;
import de.raindancer.modules.guilds.model.Standing;
import de.raindancer.modules.guilds.model.XpSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** What a guild earns and has bought, on two servers of one network. */
@Tag("database")
class ProgressBookTest {

    private static final long WEEK = 2_900;

    @TempDir
    Path folder;

    private TwoServers servers;
    private ProgressBook smp;
    private ProgressBook arena;
    private String elves;

    @BeforeEach
    void start() {
        servers = new TwoServers(folder);
        GuildBook guilds = servers.book("smp");
        Guild guild = Fixtures.guild("Elves");
        guilds.create(guild);
        elves = guild.id().value();
        smp = servers.progress("smp");
        arena = servers.progress("arena");
    }

    @AfterEach
    void stop() {
        servers.close();
    }

    private static <T> List<T> together(Callable<T> first, Callable<T> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> running = new ArrayList<>();
        for (Callable<T> each : List.of(first, second)) {
            running.add(pool.submit(() -> {
                go.await();
                return each.call();
            }));
        }
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> each : running) {
            results.add(each.get());
        }
        pool.shutdown();
        return results;
    }

    private List<Contract> deal() {
        return List.of(new Contract(elves, WEEK, 0, XpSource.MINE, "", "Mine ore", 10, 0, 500, false),
                new Contract(elves, WEEK, 1, XpSource.FISH, "", "Catch fish", 50, 0, 300, false));
    }

    @Test
    @DisplayName("experience by source and by week is counted on both servers and written as additions")
    void experienceAddsUp() {
        smp.add(elves, XpSource.MINE, 30, WEEK);
        arena.add(elves, XpSource.MINE, 12, WEEK);
        arena.add(elves, XpSource.FISH, 5, WEEK);
        assertThat(smp.totals(elves)).as("its own, at once").containsEntry(XpSource.MINE, 30L);
        assertThat(smp.flush()).isTrue();
        assertThat(arena.flush()).isTrue();
        servers.settle();
        for (ProgressBook each : List.of(smp, arena)) {
            assertThat(each.totals(elves)).containsEntry(XpSource.MINE, 42L).containsEntry(XpSource.FISH, 5L);
            assertThat(each.weekXp(WEEK)).containsEntry(elves, 47L);
        }
        ProgressBook restarted = servers.progress("third");
        assertThat(restarted.totals(elves)).containsEntry(XpSource.MINE, 42L);
    }

    @Test
    @DisplayName("experience for a guild that is gone is not written, so a guild founded again starts from nothing")
    void goneGuildEarnsNothing() {
        smp.add("ghosts", XpSource.MINE, 30, WEEK);
        assertThat(smp.flush()).isTrue();
        assertThat(servers.progress("third").totals("ghosts")).isEmpty();
    }

    @Test
    @DisplayName("contracts are dealt once: the second server finds the first one's, whatever it would have dealt")
    void dealtOnce() {
        List<Contract> first = smp.contracts(elves, WEEK, this::deal);
        List<Contract> second = arena.contracts(elves, WEEK, () -> List.of(
                new Contract(elves, WEEK, 0, XpSource.BREED, "", "Other", 5, 0, 1, false)));
        assertThat(second).extracting(Contract::title).isEqualTo(first.stream().map(Contract::title).toList());
    }

    @Test
    @DisplayName("a contract both servers push to the goal is finished once: one server gets the reward")
    void finishedOnce() throws Exception {
        smp.contracts(elves, WEEK, this::deal);
        servers.settle();
        arena.contracts(elves, WEEK, this::deal);
        List<Contract> told = Collections.synchronizedList(new ArrayList<>());
        smp.onContractFinished(told::add);
        arena.onContractFinished(told::add);

        List<List<Contract>> finishedHere = together(
                () -> smp.advance(elves, WEEK, new Activity(XpSource.MINE, "", 10)),
                () -> arena.advance(elves, WEEK, new Activity(XpSource.MINE, "", 10)));
        smp.flush();
        arena.flush();
        servers.settle();
        int rewards = finishedHere.stream().mapToInt(List::size).sum() + told.size();
        assertThat(rewards).as("rewards paid for one contract").isEqualTo(1);
        for (ProgressBook each : List.of(smp, arena)) {
            assertThat(each.contracts(elves, WEEK, List::of).getFirst().done()).isTrue();
            assertThat(each.contracts(elves, WEEK, List::of).getFirst().progress()).isEqualTo(10);
        }
    }

    @Test
    @DisplayName("a contract two servers finish between them, neither alone, is finished once, by the write that reaches the goal")
    void finishedBetweenThem() {
        smp.contracts(elves, WEEK, this::deal);
        servers.settle();
        arena.contracts(elves, WEEK, this::deal);
        List<Contract> told = Collections.synchronizedList(new ArrayList<>());
        smp.onContractFinished(told::add);
        arena.onContractFinished(told::add);
        List<Contract> first = smp.advance(elves, WEEK, new Activity(XpSource.MINE, "", 6));
        List<Contract> second = arena.advance(elves, WEEK, new Activity(XpSource.MINE, "", 6));
        assertThat(first).as("neither has ten by its own count").isEmpty();
        assertThat(second).isEmpty();
        smp.flush();
        arena.flush();
        assertThat(told).as("the write that took it to the goal finished it, and told this server").hasSize(1);
        servers.settle();
        assertThat(smp.contracts(elves, WEEK, List::of).getFirst().progress()).isEqualTo(10);
        assertThat(smp.contracts(elves, WEEK, List::of).getFirst().done()).isTrue();
    }

    @Test
    @DisplayName("two servers spending a guild's last point at once: one of them gets the perk")
    void onePoint() throws Exception {
        smp.standing(elves, () -> 1);
        servers.settle();
        List<Integer> bought = together(
                () -> spend(smp, Perk.HASTE),
                () -> spend(arena, Perk.LUCK));
        assertThat(bought.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        servers.settle();
        Standing now = servers.progress("third").standing(elves, () -> 0);
        assertThat(now.spent()).isEqualTo(1);
        assertThat(now.levelPoints()).isEqualTo(1);
    }

    private int spend(ProgressBook book, Perk perk) {
        int[] spent = new int[1];
        book.standing(elves, () -> 1, standing -> {
            if (standing.levelPoints() - standing.spent() < 1) {
                spent[0] = 0;
                return standing;
            }
            spent[0] = 1;
            return standing.withPerk(perk, standing.rank(perk) + 1);
        });
        return spent[0];
    }

    @Test
    @DisplayName("level points gained on two servers at once are both in the standing")
    void pointsAddUp() {
        smp.standing(elves, () -> 0);
        servers.settle();
        smp.standing(elves, () -> 0, standing -> standing.withLevelPoints(standing.levelPoints() + 2));
        arena.standing(elves, () -> 0, standing -> standing.withLevelPoints(standing.levelPoints() + 3));
        assertThat(servers.progress("third").standing(elves, () -> 0).levelPoints()).isEqualTo(5);
    }

    @Test
    @DisplayName("a week's crown is settled by one server, however many try")
    void crownOnce() throws Exception {
        List<Boolean> settled = together(
                () -> smp.settleCrown(new ProgressStorage.CrownState(elves, WEEK, WEEK - 1)),
                () -> arena.settleCrown(new ProgressStorage.CrownState(elves, WEEK, WEEK - 1)));
        assertThat(settled).containsExactlyInAnyOrder(true, false);
        servers.settle();
        assertThat(smp.crown().guild()).isEqualTo(elves);
        assertThat(arena.crown().settledWeek()).isEqualTo(WEEK - 1);
        assertThat(smp.settleCrown(new ProgressStorage.CrownState("", WEEK + 1, WEEK))).isTrue();
    }

    @Test
    @DisplayName("a boost staff start on one server is on all of them")
    void boostEverywhere() {
        smp.boost(new Boost(50, 9_999_999, Optional.of(Fixtures.LEADER)));
        servers.settle();
        assertThat(arena.boost().percent()).isEqualTo(50);
        assertThat(arena.boost().by()).contains(Fixtures.LEADER);
    }

    @Test
    @DisplayName("a database that cannot be read leaves what is known")
    void failedReadKeepsMemory() {
        de.raindancer.core.data.sql.Database mine = servers.separate();
        ProgressBook blinking = new ProgressBook(mine);
        blinking.load();
        smp.add(elves, XpSource.MINE, 3, WEEK);
        smp.flush();
        blinking.refresh(elves);
        assertThat(blinking.totals(elves)).containsEntry(XpSource.MINE, 3L);
        mine.close();
        assertThat(blinking.refresh(elves)).isFalse();
        assertThat(blinking.totals(elves)).containsEntry(XpSource.MINE, 3L);
    }
}
