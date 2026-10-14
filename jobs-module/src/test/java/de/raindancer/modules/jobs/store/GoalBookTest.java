package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.GoalKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class GoalBookTest {

    @TempDir
    Path folder;

    private TwoServers servers;
    private final UUID ana = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();

    @BeforeEach
    void start() {
        servers = new TwoServers(folder);
    }

    @AfterEach
    void stop() {
        servers.close();
    }

    private static Goal goal(long number, String template, int amount) {
        return new Goal(number, template, "Goal " + template, "COD", GoalKind.DELIVER,
                new ItemSelection(List.of(Category.FOOD), List.of("cod"), List.of("cooked_cod")), amount, 10, 99, Map.of());
    }

    @Test
    @DisplayName("goals, who gave what, what was learned and the next number survive a restart")
    void persists() {
        GoalBook book = servers.goals("smp");
        Goal cod = goal(1, "cod", 200);
        assertThat(book.start(cod.with(ana, 40))).isTrue();
        assertThat(book.learn("cod", 300)).isTrue();

        GoalBook again = servers.goals("smp");
        assertThat(again.active()).hasSize(1);
        Goal read = again.active().getFirst();
        assertThat(read.title()).isEqualTo("Goal cod");
        assertThat(read.items().covers("COD")).isTrue();
        assertThat(read.items().covers("COOKED_COD")).isFalse();
        assertThat(read.items().covers("BREAD")).as("the food drawer came back").isTrue();
        assertThat(read.givenBy(ana)).isEqualTo(40);
        assertThat(read.endsAt()).isEqualTo(99);
        assertThat(again.learned("cod")).contains(300);
        Optional<Goal> next = again.start(5, number -> goal(number, "wheat", 10));
        assertThat(next).map(Goal::number).contains(2L);
    }

    @Test
    @DisplayName("a contribution is added to the goal and saved; a finished goal leaves the board")
    void contributing() {
        GoalBook book = servers.goals("smp");
        book.start(goal(1, "fish", 10));
        assertThat(book.add(1, ana, 4)).map(Goal::progress).contains(4);
        assertThat(book.give(1, ana, 50)).map(GoalBook.Counted::counted).as("only what the goal still needs").contains(6);
        assertThat(book.goal(1)).map(Goal::progress).contains(10);
        assertThat(book.add(999, ana, 1)).isEmpty();
        assertThat(book.end(1)).map(Goal::progress).contains(10);
        assertThat(book.active()).isEmpty();
        assertThat(book.end(1)).isEmpty();
    }

    @Test
    @DisplayName("what is owed to a player survives a restart, adds up, and is taken for payment by one claimant")
    void owed() {
        GoalBook book = servers.goals("smp");
        assertThat(book.owe(ana, Money.of(300))).isTrue();
        assertThat(book.owe(ana, Money.of(200))).isTrue();

        GoalBook again = servers.goals("smp");
        assertThat(again.owed()).containsEntry(ana, Money.of(500));
        assertThat(again.claimOwed(ana)).contains(Money.of(500));
        assertThat(book.claimOwed(ana)).as("already claimed").isEmpty();
        assertThat(again.owed()).isEmpty();
    }

    @Test
    @DisplayName("a database that cannot be used is not an empty board: nothing is written, nothing is lost")
    void unusable() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), JobsDatabase.SCHEMA);
        GoalBook book = new GoalBook(broken);
        book.load();
        assertThat(book.readable()).isFalse();
        assertThat(book.learn("cod", 5)).isFalse();
        assertThat(book.start(goal(1, "cod", 5))).isFalse();
        broken.close();
    }

    @Test
    @DisplayName("a read that fails keeps the board as it is")
    void failedReadKeepsMemory() {
        Database mine = servers.database();
        GoalBook smp = new GoalBook(mine);
        smp.load();
        smp.connect(servers.hub.bus("smp"));
        GoalBook arena = servers.goals("arena");
        arena.start(goal(1, "cod", 20));
        servers.settle();
        assertThat(smp.active()).hasSize(1);

        mine.close();
        arena.start(5, number -> goal(number, "wheat", 10));
        servers.settle();
        assertThat(smp.active()).as("the database blinked").hasSize(1);
    }

    @Test
    @DisplayName("a goal put up on one server is on the board of the other; what is given on either counts on both")
    void oneBoard() {
        GoalBook smp = servers.goals("smp");
        GoalBook arena = servers.goals("arena");
        assertThat(smp.start(3, number -> goal(number, "cod", 100))).isPresent();
        servers.settle();
        assertThat(arena.active()).extracting(Goal::template).containsExactly("cod");

        smp.add(1, ana, 30);
        arena.add(1, bo, 25);
        arena.add(1, ana, 5);
        servers.settle();
        assertThat(smp.goal(1)).map(Goal::progress).contains(60);
        assertThat(arena.goal(1)).map(Goal::progress).contains(60);
        assertThat(smp.goal(1).orElseThrow().givenBy(ana)).isEqualTo(35);
    }

    @Test
    @DisplayName("catches counted on both servers at once are both written, as additions")
    void catches() {
        GoalBook smp = servers.goals("smp");
        GoalBook arena = servers.goals("arena");
        smp.start(3, number -> goal(number, "fish", 100));
        servers.settle();
        for (int i = 0; i < 10; i++) {
            smp.addLater(1, ana, 1);
            arena.addLater(1, ana, 2);
        }
        assertThat(smp.goal(1)).map(Goal::progress).as("each sees its own at once").contains(10);
        assertThat(smp.flush()).isTrue();
        assertThat(arena.flush()).isTrue();
        servers.settle();
        assertThat(smp.goal(1)).map(Goal::progress).contains(30);
        assertThat(arena.goal(1)).map(Goal::progress).contains(30);
    }

    @Test
    @DisplayName("two servers handing in more than the goal needs: it takes no more than it needs, and says how much")
    void neverPastTheGoal() throws Exception {
        GoalBook smp = servers.goals("smp");
        GoalBook arena = servers.goals("arena");
        smp.start(3, number -> goal(number, "cod", 100));
        servers.settle();
        GoalBook.Counted first = smp.give(1, ana, 80).orElseThrow();
        GoalBook.Counted second = arena.give(1, bo, 80).orElseThrow();
        assertThat(first.counted() + second.counted()).isEqualTo(100);
        assertThat(second.counted()).isEqualTo(20);
        servers.settle();
        assertThat(smp.goal(1)).map(Goal::reached).contains(true);
    }

    @Test
    @DisplayName("a goal ending on two servers at once is handed to one of them, so it is paid once")
    void endsOnce() throws Exception {
        GoalBook smp = servers.goals("smp");
        GoalBook arena = servers.goals("arena");
        smp.start(3, number -> goal(number, "cod", 10));
        smp.add(1, ana, 10);
        servers.settle();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<Goal>>> ended = new ArrayList<>();
        for (GoalBook book : List.of(smp, arena)) {
            ended.add(pool.submit(() -> {
                go.await();
                return book.end(1);
            }));
        }
        go.countDown();
        int winners = 0;
        for (Future<Optional<Goal>> each : ended) {
            winners += each.get().isPresent() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
        servers.settle();
        assertThat(smp.active()).isEmpty();
        assertThat(arena.active()).isEmpty();
    }

    @Test
    @DisplayName("two servers filling a board that has room for one put up one goal, and never one kind twice")
    void boardSize() throws Exception {
        GoalBook smp = servers.goals("smp");
        GoalBook arena = servers.goals("arena");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Optional<Goal>>> started = new ArrayList<>();
        started.add(pool.submit(() -> {
            go.await();
            return smp.start(1, number -> goal(number, "cod", 10));
        }));
        started.add(pool.submit(() -> {
            go.await();
            return arena.start(1, number -> goal(number, "wheat", 10));
        }));
        go.countDown();
        int made = 0;
        for (Future<Optional<Goal>> each : started) {
            made += each.get().isPresent() ? 1 : 0;
        }
        pool.shutdown();
        assertThat(made).isEqualTo(1);
        assertThat(smp.start(5, number -> goal(number, "cod", 10)).isPresent() && arena.start(5, number -> goal(number, "cod", 10)).isPresent())
                .as("one kind once").isFalse();
        assertThat(smp.active()).extracting(Goal::template).doesNotHaveDuplicates();
    }
}
