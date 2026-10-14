package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.Order;
import de.raindancer.modules.jobs.model.Quest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class JobsImportTest {

    @TempDir
    Path folder;

    private final UUID ana = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final List<String> problems = new ArrayList<>();
    private TwoServers servers;
    private Database database;

    @BeforeEach
    void start() throws Exception {
        servers = new TwoServers(folder);
        database = servers.database();
        Files.writeString(folder.resolve("goals.yml"), """
                next-number: 8
                learned:
                  cod: 300
                owed:
                  %s: 500
                goals:
                  7:
                    template: cod
                    title: Cod
                    icon: COD
                    kind: DELIVER
                    items: [ cod ]
                    amount: 200
                    started-at: 10
                    ends-at: 99
                    given:
                      %s: 40
                """.formatted(ana, bo));
        Files.writeString(folder.resolve("quest-progress.yml"), """
                players:
                  %s:
                    day: 2026-10-10
                    tier: 2
                    rerolls: 1
                    before: [ walk ]
                    quests:
                      - { template: quarry, amount: 300, pay: 900, progress: 120, state: OPEN }
                """.formatted(ana));
        Files.writeString(folder.resolve("order-progress.yml"), """
                players:
                  %s:
                    day: 2026-10-10
                    taken: 2
                    rerolls: 0
                    offered: false
                    order: { work: zombies, says: 40 zombies, units: 40, pay: 500, started-at: 1, ends-at: 9, progress: 3, state: OPEN }
                """.formatted(ana));
    }

    @AfterEach
    void stop() {
        servers.close();
    }

    private List<String> run() {
        return new JobsImport(database, "smp", problems::add).run(new YamlStore(folder.resolve("goals.yml")),
                new YamlStore(folder.resolve("quest-progress.yml")), new YamlStore(folder.resolve("order-progress.yml")));
    }

    @Test
    @DisplayName("what a server kept before the network comes into the shared database, once")
    void brought() {
        assertThat(run()).containsExactly("goals", "quests", "orders");
        assertThat(run()).as("only once").isEmpty();

        GoalBook goals = new GoalBook(database);
        goals.load();
        assertThat(goals.goal(7)).map(Goal::amount).contains(200);
        assertThat(goals.goal(7).orElseThrow().givenBy(bo)).isEqualTo(40);
        assertThat(goals.learned("cod")).contains(300);
        assertThat(goals.owed()).containsEntry(ana, Money.of(500));
        assertThat(goals.start(5, number -> new Goal(number, "wheat", "W", "WHEAT", goals.goal(7).orElseThrow().kind(),
                goals.goal(7).orElseThrow().items(), 5, 1, 2, java.util.Map.of())).orElseThrow().number())
                .as("numbers carry on").isEqualTo(8);

        QuestBook quests = new QuestBook(database);
        assertThat(quests.of(ana).orElseThrow().quests()).extracting(Quest::progress).containsExactly(120);
        OrderBook orders = new OrderBook(database);
        assertThat(orders.of(ana).orElseThrow().order()).extracting(Order::progress).isEqualTo(3);
    }

    @Test
    @DisplayName("what the network already has wins: a player's day, and a board that is already up")
    void networkWins() {
        GoalBook goals = new GoalBook(database);
        goals.load();
        goals.start(goalOf(3, "wheat"));
        QuestBook quests = new QuestBook(database);
        quests.putNow(ana, new de.raindancer.modules.jobs.model.QuestDay("2026-10-11", 5, List.of(), List.of()));
        run();
        GoalBook again = new GoalBook(database);
        again.load();
        assertThat(again.active()).extracting(Goal::template).containsExactly("wheat");
        assertThat(again.owed()).as("debts are still owed").containsEntry(ana, Money.of(500));
        assertThat(new QuestBook(database).of(ana).orElseThrow().tier()).isEqualTo(5);
    }

    private Goal goalOf(long number, String template) {
        return new Goal(number, template, "T", "WHEAT", de.raindancer.modules.jobs.model.GoalKind.DELIVER,
                de.raindancer.core.ui.choose.ItemSelection.NOTHING, 5, 1, 2, java.util.Map.of());
    }

    @Test
    @DisplayName("a document that cannot be read is left for the next start, and is not marked as brought in")
    void unreadable() throws Exception {
        Files.writeString(folder.resolve("goals.yml"), "goals: [ : : broken");
        assertThat(run()).containsExactly("quests", "orders");
        assertThat(problems).anyMatch(problem -> problem.startsWith("goals"));
        Files.writeString(folder.resolve("goals.yml"), "learned:\n  cod: 9\n");
        assertThat(run()).containsExactly("goals");
    }

    @Test
    @DisplayName("without old documents there is nothing to bring, and nothing breaks")
    void nothing() throws Exception {
        Path empty = Files.createDirectory(folder.resolve("empty"));
        assertThat(new JobsImport(database, "smp", problems::add).run(new YamlStore(empty.resolve("goals.yml")),
                new YamlStore(empty.resolve("q.yml")), new YamlStore(empty.resolve("o.yml")))).hasSize(3);
        assertThat(problems).isEmpty();
    }
}
