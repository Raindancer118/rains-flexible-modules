package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.jobs.model.Quest;
import de.raindancer.modules.jobs.model.QuestDay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuestBookTest {

    @TempDir
    Path folder;

    private final UUID ana = UUID.randomUUID();
    private final UUID bo = UUID.randomUUID();
    private final LocalDate today = LocalDate.of(2026, 10, 10);

    private QuestBook book() {
        QuestBook book = new QuestBook(new YamlStore(folder.resolve("quest-progress.yml")));
        book.load();
        return book;
    }

    @Test
    @DisplayName("a day is written and read back as it was: tier, quests, their progress and state")
    void roundTrip() {
        QuestBook book = book();
        QuestDay day = new QuestDay("2026-10-10", 3, List.of(new Quest("quarry", 300, Money.of(900), 120, Quest.State.OPEN),
                new Quest("miner-iron", 80, Money.of(1500), 80, Quest.State.OWED)), List.of("coal-run"));
        assertThat(book.putNow(ana, day)).isTrue();
        assertThat(book().of(ana)).contains(day);
        assertThat(book().owing()).containsOnlyKeys(ana);
    }

    @Test
    @DisplayName("progress waits for the flush; old days are dropped then, unless something is still owed")
    void flushAndPrune() {
        QuestBook book = book();
        book.put(ana, new QuestDay("2026-10-10", 0, List.of(new Quest("quarry", 10, Money.of(90), 4, Quest.State.OPEN)), List.of()));
        book.put(bo, new QuestDay("2026-09-01", 0, List.of(new Quest("quarry", 10, Money.of(90), 10, Quest.State.PAID)), List.of()));
        assertThat(book().of(ana)).isEmpty();
        book.flush(today);
        assertThat(book().of(ana)).isPresent();
        assertThat(book().of(bo)).as("a month old and paid").isEmpty();
    }

    @Test
    @DisplayName("an unreadable file is never written over")
    void unreadable() throws Exception {
        Files.writeString(folder.resolve("quest-progress.yml"), "players: [ this is : not yaml");
        QuestBook book = book();
        assertThat(book.readable()).isFalse();
        assertThat(book.putNow(ana, new QuestDay("2026-10-10", 0, List.of(), List.of()))).isFalse();
        assertThat(Files.readString(folder.resolve("quest-progress.yml"))).contains("not yaml");
    }
}
