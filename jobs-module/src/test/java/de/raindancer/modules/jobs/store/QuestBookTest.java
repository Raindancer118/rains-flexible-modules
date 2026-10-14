package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestDay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class QuestBookTest {

    @TempDir
    Path folder;

    private TwoServers servers;
    private final UUID ana = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 10, 10);

    @BeforeEach
    void start() {
        servers = new TwoServers(folder);
    }

    @AfterEach
    void stop() {
        servers.close();
    }

    private static QuestDay day(String date, int progress, Quest.State state) {
        return new QuestDay(date, 3, List.of(new Quest("quarry", 300, Money.of(900), progress, state),
                new Quest("miner-iron", 80, Money.of(1500), 0, Quest.State.OPEN)), List.of("coal-run"));
    }

    @Test
    @DisplayName("a day is written and read back as it was: tier, quests, their progress and state")
    void roundTrip() {
        QuestBook book = servers.quests("smp");
        QuestDay day = new QuestDay("2026-10-10", 3, List.of(new Quest("quarry", 300, Money.of(900), 120, Quest.State.OPEN),
                new Quest("miner-iron", 80, Money.of(1500), 80, Quest.State.OWED)), List.of("coal-run"), 1);
        assertThat(book.putNow(ana, day)).isTrue();
        assertThat(servers.quests("smp").of(ana)).contains(day);
        assertThat(servers.quests("smp").owing()).containsOnlyKeys(ana);
    }

    @Test
    @DisplayName("progress waits for the flush and is written as an addition; old days are dropped then, unless owed")
    void flushAndPrune() {
        QuestBook book = servers.quests("smp");
        assertThat(book.putNow(ana, day("2026-10-10", 4, Quest.State.OPEN))).isTrue();
        assertThat(book.putNow(bo, new QuestDay("2026-09-01", 0,
                List.of(new Quest("quarry", 10, Money.of(90), 10, Quest.State.PAID)), List.of()))).isTrue();
        book.put(ana, day("2026-10-10", 9, Quest.State.OPEN));
        assertThat(book.of(ana).orElseThrow().quests().getFirst().progress()).isEqualTo(9);
        assertThat(servers.quests("smp").of(ana).orElseThrow().quests().getFirst().progress()).as("not written yet").isEqualTo(4);
        book.flush(today);
        assertThat(servers.quests("smp").of(ana).orElseThrow().quests().getFirst().progress()).isEqualTo(9);
        assertThat(servers.quests("smp").of(bo)).as("a month old and paid").isEmpty();
    }

    @Test
    @DisplayName("the same player earning on two servers at once: both servers' progress counts")
    void bothCount() {
        QuestBook smp = servers.quests("smp");
        QuestBook arena = servers.quests("arena");
        assertThat(smp.putNow(ana, day("2026-10-10", 0, Quest.State.OPEN))).isTrue();
        servers.settle();
        assertThat(arena.of(ana)).isPresent();

        smp.put(ana, day("2026-10-10", 7, Quest.State.OPEN));
        arena.put(ana, day("2026-10-10", 5, Quest.State.OPEN));
        smp.flush(today);
        arena.flush(today);
        servers.settle();
        assertThat(smp.of(ana).orElseThrow().quests().getFirst().progress()).isEqualTo(12);
        assertThat(arena.of(ana).orElseThrow().quests().getFirst().progress()).isEqualTo(12);
    }

    @Test
    @DisplayName("progress is never counted past what the quest asks")
    void capped() {
        QuestBook smp = servers.quests("smp");
        QuestBook arena = servers.quests("arena");
        smp.putNow(ana, day("2026-10-10", 290, Quest.State.OPEN));
        servers.settle();
        arena.of(ana);
        smp.put(ana, day("2026-10-10", 299, Quest.State.OPEN));
        arena.put(ana, day("2026-10-10", 299, Quest.State.OPEN));
        smp.flush(today);
        arena.flush(today);
        assertThat(servers.quests("smp").of(ana).orElseThrow().quests().getFirst().progress()).isEqualTo(300);
    }

    @Test
    @DisplayName("a server that decided to pay on an open quest is refused once the other server's payment reached it")
    void decidedOnOpenButPaidMeanwhile() {
        QuestBook smp = servers.quests("smp");
        QuestBook arena = servers.quests("arena");
        smp.putNow(ana, day("2026-10-10", 290, Quest.State.OPEN));
        servers.settle();
        QuestDay seenOpen = arena.of(ana).orElseThrow();
        assertThat(smp.putNow(ana, day("2026-10-10", 300, Quest.State.PAID))).isTrue();
        servers.settle();
        arena.refresh(ana);
        assertThat(arena.putNow(ana, seenOpen, seenOpen.with(0, seenOpen.quests().getFirst().in(Quest.State.PAID))))
                .as("paid elsewhere since it looked").isFalse();
    }

    @Test
    @DisplayName("a quest done on two servers is paid by one: only one can change it from open")
    void paidOnce() {
        QuestBook smp = servers.quests("smp");
        QuestBook arena = servers.quests("arena");
        smp.putNow(ana, day("2026-10-10", 290, Quest.State.OPEN));
        servers.settle();
        QuestDay seen = arena.of(ana).orElseThrow();
        assertThat(smp.putNow(ana, day("2026-10-10", 300, Quest.State.PAID))).isTrue();
        assertThat(arena.putNow(ana, seen, day("2026-10-10", 300, Quest.State.PAID))).as("already paid elsewhere").isFalse();
        servers.settle();
        assertThat(arena.of(ana).orElseThrow().quests().getFirst().state()).isEqualTo(Quest.State.PAID);
    }

    @Test
    @DisplayName("two servers handing out today's quests at once: one day is written, the other server reads it")
    void oneDayWins() {
        QuestBook smp = servers.quests("smp");
        QuestBook arena = servers.quests("arena");
        assertThat(smp.known(ana)).isTrue();
        assertThat(arena.known(ana)).isTrue();
        assertThat(smp.putNow(ana, day("2026-10-10", 0, Quest.State.OPEN))).isTrue();
        QuestDay other = new QuestDay("2026-10-10", 1, List.of(new Quest("walk", 100, Money.of(50), 0, Quest.State.OPEN)), List.of());
        assertThat(arena.putNow(ana, other)).isFalse();
        assertThat(arena.of(ana).orElseThrow().tier()).as("theirs").isEqualTo(3);
    }

    @Test
    @DisplayName("a player leaving writes what they counted, and the server they go to reads it")
    void followsThem() {
        QuestBook smp = servers.quests("smp");
        QuestBook arena = servers.quests("arena");
        smp.putNow(ana, day("2026-10-10", 0, Quest.State.OPEN));
        smp.put(ana, day("2026-10-10", 11, Quest.State.OPEN));
        smp.forget(ana);
        assertThat(arena.refresh(ana)).isTrue();
        assertThat(arena.of(ana).orElseThrow().quests().getFirst().progress()).isEqualTo(11);
    }

    @Test
    @DisplayName("a database that cannot be read is not 'no quests': nothing is handed out or written")
    void unreadable() throws Exception {
        Database broken = TestDatabases.unusable(folder.resolve("broken.db"), JobsDatabase.SCHEMA);
        QuestBook book = new QuestBook(broken);
        book.load();
        assertThat(book.readable()).isFalse();
        assertThat(book.known(ana)).isFalse();
        assertThat(book.putNow(ana, day("2026-10-10", 0, Quest.State.OPEN))).isFalse();
        broken.close();
    }

    @Test
    @DisplayName("clearing a player's day takes the quests with it")
    void clearing() {
        QuestBook book = servers.quests("smp");
        book.putNow(ana, day("2026-10-10", 0, Quest.State.OPEN));
        assertThat(book.clear(ana)).isTrue();
        assertThat(servers.quests("smp").of(ana)).isEmpty();
    }

    @Test
    @DisplayName("a read that fails keeps the day as it is held")
    void failedReadKeepsMemory() {
        Database mine = servers.database();
        QuestBook book = new QuestBook(mine);
        book.load();
        assertThat(book.putNow(ana, day("2026-10-10", 5, Quest.State.OPEN))).isTrue();
        mine.close();
        assertThat(book.refresh(ana)).isFalse();
        assertThat(book.of(ana).orElseThrow().quests().getFirst().progress()).isEqualTo(5);
    }
}
