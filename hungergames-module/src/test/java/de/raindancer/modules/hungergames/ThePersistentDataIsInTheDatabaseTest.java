package de.raindancer.modules.hungergames;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.store.RuntimeStore;
import de.raindancer.modules.hungergames.store.TributeRoster;
import de.raindancer.modules.hungergames.store.YamlSessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("database")
@DisplayName("what the module keeps is documents in the database; no file of its own is written")
class ThePersistentDataIsInTheDatabaseTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @TempDir
    Path plugins;

    private Database database;
    private Documents documents;
    private Path data;
    private LocalDateTime now = LocalDateTime.of(2026, 1, 1, 20, 0, 0);

    @BeforeEach
    void open() {
        database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        data = plugins.resolve("RainsHungerGames");
    }

    @AfterEach
    void close() {
        database.close();
    }

    private YamlStore yaml(String name) {
        return new YamlStore(data.resolve(name), documents, Documents.Scope.SERVER);
    }

    private DocumentFolder rounds() {
        return DocumentFolder.on(data.resolve("rounds"), documents, Documents.Scope.SERVER);
    }

    private RoundLogService roundLog() {
        RoundLogService service = new RoundLogService(data.resolve("rounds"), rounds(), id -> id.equals(ALICE) ? "Alice"
                : id.equals(BOB) ? "Bob" : id.toString(), id -> id.value(), mock(LogChannel.class), () -> now, Runnable::run);
        service.settings(HungerGamesSettings.DEFAULTS);
        return service;
    }

    private String text(String name) {
        return rounds().store(name).readText().orElse("");
    }

    @Test
    @DisplayName("a round's log is one document, lines in order, readable as text")
    void roundLogIsADocument() {
        RoundLogService log = roundLog();
        log.kill(ALICE, BOB, 1);
        log.participantEliminated(BOB, ALICE, 3);

        assertThat(rounds().names(".log")).containsExactly("round-20260101-200000.log");
        assertThat(text("round-20260101-200000.log")).containsSubsequence("[KILL] Alice kills Bob", "[ELIMINATION] Bob by Alice");
        assertThat(data.resolve("rounds")).doesNotExist();
    }

    @Test
    @DisplayName("the next round is a document of its own; the earlier one is left as it was")
    void oneDocumentPerRound() {
        RoundLogService log = roundLog();
        log.kill(ALICE, BOB, 1);

        now = now.plusHours(1);
        log.phaseChanged(GamePhase.FINISHED, GamePhase.PREFLIGHT);
        log.kill(BOB, ALICE, 1);

        assertThat(rounds().names(".log")).containsExactly("round-20260101-200000.log", "round-20260101-210000.log");
        assertThat(text("round-20260101-200000.log")).doesNotContain("PREFLIGHT");
        assertThat(text("round-20260101-210000.log")).contains("Bob kills Alice");
    }

    @Test
    @DisplayName("pooled into one log, every round goes on the same document, and an old rounds.log is carried on")
    void pooled() throws Exception {
        Files.createDirectories(data.resolve("rounds"));
        Files.writeString(data.resolve("rounds").resolve("rounds.log"), "from 1.x\n");
        RoundLogService log = roundLog();
        log.settings(Tweak.of(HungerGamesSettings.DEFAULTS, "roundLogFilePerRound", false));

        log.kill(ALICE, BOB, 1);

        assertThat(text("rounds.log")).startsWith("from 1.x\n").contains("Alice kills Bob");
        assertThat(data.resolve("rounds").resolve("rounds.log")).doesNotExist();
        assertThat(data.resolve("rounds").resolve("rounds.log.imported-2.0.0")).exists();
    }

    @Test
    @DisplayName("session and runtime state are documents, and clearing them removes the document")
    void sessionAndRuntime() {
        YamlStore session = yaml("session.yml");
        session.write(y -> y.set("phase", "LOBBY"));
        YamlSessionStore sessions = new YamlSessionStore(session);
        sessions.clear();
        assertThat(yaml("session.yml").exists()).isFalse();

        RuntimeStore runtime = new RuntimeStore(yaml("runtime.yml"));
        runtime.saveOpSnapshot(java.util.Set.of(ALICE));
        assertThat(yaml("runtime.yml").exists()).isTrue();
        runtime.clear();
        assertThat(yaml("runtime.yml").exists()).isFalse();
    }

    @Test
    @DisplayName("the sign-up sheet is created as a document, once, and is read back")
    void signUpSheet() {
        TributeRoster roster = new TributeRoster(yaml("tributes.yml"));

        assertThat(roster.createIfMissing()).isTrue();
        assertThat(roster.createIfMissing()).isFalse();
        assertThat(roster.remember("Katniss")).isTrue();

        assertThat(new TributeRoster(yaml("tributes.yml")).load().found())
                .extracting(TributeRoster.Entry::name).containsExactly("Katniss");
        assertThat(data.resolve("tributes.yml")).doesNotExist();
    }
}
