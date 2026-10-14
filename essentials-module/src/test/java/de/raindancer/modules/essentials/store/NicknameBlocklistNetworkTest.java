package de.raindancer.modules.essentials.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.essentials.rules.NicknameRule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** The blocklist is the owner's, edited once for the whole network, and lives in the database like everything else. */
@Tag("database")
class NicknameBlocklistNetworkTest {

    private static final String BUNDLED = """
            # Names nobody may wear.
            # Edit in game or here.
            politicians:
              enabled: true
              action: report
              names:
                - donald trump
            """;

    @TempDir
    Path plugins;

    private Servers servers;

    private NicknameBlocklist on(String id) {
        if (servers == null) {
            servers = new Servers(plugins);
        }
        NicknameBlocklist blocklist = new NicknameBlocklist(servers.network(servers.server(id), "blocklist.yml"),
                () -> new ByteArrayInputStream(BUNDLED.getBytes(StandardCharsets.UTF_8)));
        blocklist.load();
        blocklist.watch();
        return blocklist;
    }

    @AfterEach
    void close() {
        if (servers != null) {
            servers.close();
        }
    }

    @Test
    @DisplayName("the bundled list seeds the document once, and both servers read the same one; no file is written")
    void seededOnce() throws Exception {
        NicknameBlocklist smp = on("smp");
        NicknameBlocklist arena = on("arena");

        assertThat(smp.matchOf("Donald Trump")).isEqualTo(NicknameRule.BlockMatch.REPORTED);
        assertThat(arena.matchOf("Donald Trump")).isEqualTo(NicknameRule.BlockMatch.REPORTED);
        assertThat(servers.file("blocklist.yml")).doesNotExist();
    }

    @Test
    @DisplayName("a name the owner adds on one server is blocked on the other as soon as it is written")
    void editOnceEverywhere() {
        NicknameBlocklist smp = on("smp");
        NicknameBlocklist arena = on("arena");

        assertThat(smp.addName("politicians", "Angela Merkel")).isTrue();
        servers.heard();

        assertThat(arena.matchOf("angela merkel")).isEqualTo(NicknameRule.BlockMatch.REPORTED);
        assertThat(arena.setEnabled("politicians", false)).isTrue();
        servers.heard();
        assertThat(smp.matchOf("donald trump")).isEqualTo(NicknameRule.BlockMatch.NONE);
    }

    @Test
    @DisplayName("the opening comment is kept through an edit made on another server")
    void headerKept() throws Exception {
        NicknameBlocklist smp = on("smp");
        smp.addName("politicians", "Angela Merkel");

        String text = servers.network(servers.server("arena"), "blocklist.yml").readText().orElseThrow();

        assertThat(text).startsWith("# Names nobody may wear.\n# Edit in game or here.\n");
        assertThat(text).contains("angela merkel");
    }

    @Test
    @DisplayName("an owner's 1.x blocklist.yml is brought in and wins over the bundled list")
    void oldFileIsBroughtIn() throws Exception {
        servers = new Servers(plugins);
        Files.createDirectories(servers.file("blocklist.yml").getParent());
        Files.writeString(servers.file("blocklist.yml"), """
                owners-own-section:
                  enabled: true
                  action: ban
                  names:
                    - mine
                """);

        NicknameBlocklist smp = on("smp");

        assertThat(smp.matchOf("mine")).isEqualTo(NicknameRule.BlockMatch.BANNED);
        assertThat(smp.matchOf("donald trump")).isEqualTo(NicknameRule.BlockMatch.NONE);
        assertThat(servers.file("blocklist.yml")).doesNotExist();
    }

    @Test
    @DisplayName("a database that cannot be read neither throws nor writes the bundled list over what may be there")
    void unreadableIsLeftAlone() throws Exception {
        Database broken = TestDatabases.unusable(plugins.resolve("broken.db"), Documents.SCHEMA);
        try {
            Documents documents = new Documents(broken, null, plugins, "smp", System::currentTimeMillis);
            NicknameBlocklist blocklist = new NicknameBlocklist(new YamlStore(
                    plugins.resolve("RainsEssentials").resolve("blocklist.yml"), documents, Documents.Scope.NETWORK),
                    () -> new ByteArrayInputStream(BUNDLED.getBytes(StandardCharsets.UTF_8)));

            blocklist.load();

            assertThat(blocklist.matchOf("donald trump")).isEqualTo(NicknameRule.BlockMatch.NONE);
            assertThat(blocklist.addName("politicians", "x")).isFalse();
        } finally {
            broken.close();
        }
    }
}
