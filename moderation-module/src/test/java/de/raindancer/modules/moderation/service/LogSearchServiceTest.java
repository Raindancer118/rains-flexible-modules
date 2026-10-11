package de.raindancer.modules.moderation.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.chatlog.ChatLog;
import de.raindancer.core.moderation.punishment.BlockedAttempts;
import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.LogQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LogSearchServiceTest {

    @TempDir
    Path folder;

    private static long berlin(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, LogSearchService.ZONE).toInstant().toEpochMilli();
    }

    private final AtomicLong clock = new AtomicLong(berlin(10, 17, 0));
    private final UUID mushy = UUID.fromString("e392eece-9274-4560-8672-32185a9b2f55");
    private final UUID bo = UUID.randomUUID();
    private Database chatDb;
    private Database auditDb;
    private ChatLog chatLog;
    private Audit audit;
    private BlockedAttempts attempts;
    private LogSearchService search;

    @BeforeEach
    void open() {
        chatDb = Database.open(folder.resolve("chatlog.db"), ChatLog.SCHEMA, () -> false);
        auditDb = Database.open(folder.resolve("audit.db"), CoreSchema.AUDIT, () -> false);
        chatLog = new ChatLog(chatDb, clock::get);
        audit = new Audit(auditDb, clock::get);
        attempts = new BlockedAttempts(audit, clock::get);
        Map<String, UUID> current = Map.of("mushythefish", mushy, "bo", bo);
        search = new LogSearchService(() -> chatLog, () -> attempts,
                typed -> Optional.ofNullable(current.get(typed.toLowerCase(java.util.Locale.ROOT))));
        say(17, 20, mushy, "MushyTheFish", "before the window");
        say(17, 35, mushy, "MushyTheFish", "did you read the RULES");
        say(17, 40, bo, "Bo", "the rules say no griefing");
        say(17, 50, mushy, "OldMushy", "under my old name, rules again");
        say(18, 5, mushy, "MushyTheFish", "after the window, rules");
    }

    private void say(int hour, int minute, UUID who, String name, String text) {
        clock.set(berlin(10, hour, minute));
        chatLog.record(who, name, ChatLog.PUBLIC, text);
    }

    @AfterEach
    void close() {
        chatDb.close();
        auditDb.close();
    }

    private LogQuery query(String typed) {
        return LogQuery.parse(typed.split(" "), LogSearchService.ZONE, Instant.ofEpochMilli(berlin(11, 12, 0)));
    }

    @Test
    @DisplayName("a player by name, a Berlin window and a word: exactly the lines staff mean, newest first")
    void playerTimeWord() {
        LogSearchService.Page<ChatLog.Line> page = search.chat(
                query("player:MushyTheFish from:2026-10-10 17:30 to:2026-10-10 18:00 rules"), 10);
        assertThat(page.nobody()).isEmpty();
        assertThat(page.items()).extracting(ChatLog.Line::text)
                .containsExactly("under my old name, rules again", "did you read the RULES");
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    @DisplayName("an old name finds the same player, and so does the id")
    void oldNamesAndIds() {
        assertThat(search.chat(query("player:oldmushy"), 10).total()).isEqualTo(4);
        assertThat(search.chat(query("player:" + mushy), 10).total()).isEqualTo(4);
        assertThat(search.chat(query("player:Bo player:OldMushy rules"), 10).total()).isEqualTo(4);
    }

    @Test
    @DisplayName("a name nobody has had is said back rather than searching everybody")
    void nobody() {
        LogSearchService.Page<ChatLog.Line> page = search.chat(query("player:Ghost"), 10);
        assertThat(page.nobody()).containsExactly("Ghost");
        assertThat(page.items()).isEmpty();
    }

    @Test
    @DisplayName("pages count from the newest; the last page holds what is left")
    void paging() {
        LogSearchService.Page<ChatLog.Line> first = search.chat(query("day:2026-10-10"), 2);
        assertThat(first.total()).isEqualTo(5);
        assertThat(first.pages()).isEqualTo(3);
        assertThat(first.items()).extracting(ChatLog.Line::text).first().isEqualTo("after the window, rules");
        LogSearchService.Page<ChatLog.Line> last = search.chat(query("day:2026-10-10 page:3"), 2);
        assertThat(last.items()).extracting(ChatLog.Line::text).containsExactly("before the window");
        assertThat(search.chat(query("day:2026-10-09"), 2).total()).isZero();
    }

    @Test
    @DisplayName("blocked attempts are found by player, kind, Berlin time and words in what was tried")
    void attempts() {
        Punishment ban = new Punishment("b", mushy, PunishmentKind.BAN, null, "griefing", Instant.EPOCH, null,
                null, null, null);
        Punishment mute = new Punishment("m", bo, PunishmentKind.MUTE, null, "spam", Instant.EPOCH, null,
                null, null, null);
        clock.set(berlin(11, 12, 54));
        attempts.record(mushy, "MushyTheFish", BlockedAttempts.Kind.JOIN, ban, "", "abc");
        clock.set(berlin(11, 12, 56));
        attempts.record(bo, "Bo", BlockedAttempts.Kind.CHAT, mute, "let me talk", null);
        clock.set(berlin(11, 13, 10));
        attempts.record(mushy, "MushyTheFish", BlockedAttempts.Kind.JOIN, ban, "", "abc");

        assertThat(search.attempts(query("player:MushyTheFish"), 10).total()).isEqualTo(2);
        assertThat(search.attempts(query("type:chat"), 10).items()).singleElement()
                .extracting(BlockedAttempts.Attempt::detail).isEqualTo("let me talk");
        assertThat(search.attempts(query("from:2026-10-11 12:50 to:2026-10-11 13:00"), 10).total()).isEqualTo(2);
        assertThat(search.attempts(query("talk"), 10).total()).isEqualTo(1);
        assertThat(search.attempts(query("griefing"), 10).total()).isEqualTo(2);
        assertThat(search.unknownTypes(query("type:join type:fly"))).containsExactly("fly");
    }
}
