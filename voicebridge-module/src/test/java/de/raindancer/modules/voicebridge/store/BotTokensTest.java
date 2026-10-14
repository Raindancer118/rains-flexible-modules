package de.raindancer.modules.voicebridge.store;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Secrets;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("database")
class BotTokensTest {

    private static final String SECRET_NAME = "voicebridge-tokens-smp";

    @TempDir
    Path folder;

    private Database database;
    private Secrets secrets;

    private Path old() {
        return folder.resolve("discord-token.txt");
    }

    private Path document() {
        return folder.resolve("discord-tokens.yml");
    }

    private BotTokens tokens(Map<String, String> env) {
        return tokens("smp", env);
    }

    private BotTokens tokens(String serverId, Map<String, String> env) {
        if (database == null) {
            database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
            secrets = new Secrets(database, System::currentTimeMillis);
        }
        return new BotTokens(secrets, serverId, new YamlStore(document()), old(), env::get, Log.of("test"));
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("nothing anywhere leaves an explained template, and reads as no token")
    void writesTemplate() throws Exception {
        BotTokens tokens = tokens(Map.of());

        assertThat(tokens.read()).isEmpty();
        assertThat(Files.readString(document())).contains("#").contains("tokens: []").contains("/voicebridge token add");
        assertThat(Files.readString(document())).doesNotContain("/documents import");
        assertThat(tokens.source()).isEqualTo(BotTokens.Source.NONE);
        assertThat(old()).doesNotExist();
    }

    @Test
    @DisplayName("the 1.x file moves into the secret, comments and blanks skipped, tokens trimmed, and is kept renamed")
    void importsTheOldFile() throws Exception {
        Files.writeString(old(), "# main\nmain.token\n\n# lines\n  line.one \nline.two\n");
        BotTokens tokens = tokens(Map.of());

        assertThat(tokens.readAll()).containsExactly("main.token", "line.one", "line.two");
        assertThat(tokens.source()).isEqualTo(BotTokens.Source.DATABASE);
        assertThat(secrets.text(SECRET_NAME)).contains("main.token\nline.one\nline.two");
        assertThat(old()).doesNotExist();
        assertThat(folder.resolve("discord-token.txt.imported-2.0.0")).exists();
        assertThat(Files.readString(document())).doesNotContain("main.token").doesNotContain("line.one");
        assertThat(tokens(Map.of()).readAll()).as("from the secret now").containsExactly("main.token", "line.one", "line.two");
    }

    @Test
    @DisplayName("tokens in the document move into the secret, in order, and the document is rewritten without them")
    void migratesTheDocument() throws Exception {
        Files.writeString(document(), "tokens:\n  - ' main.token '\n  - line.one\n");
        BotTokens tokens = tokens(Map.of());

        assertThat(tokens.read()).isEqualTo("main.token");
        assertThat(secrets.text(SECRET_NAME)).contains("main.token\nline.one");
        String left = Files.readString(document());
        assertThat(left).doesNotContain("main.token").doesNotContain("line.one").contains("tokens: []").contains("#");
        assertThat(tokens.readAll()).containsExactly("main.token", "line.one");
    }

    @Test
    @DisplayName("document tokens are added to ones already stored, none twice, none lost")
    void mergesWithTheStored() throws Exception {
        BotTokens first = tokens(Map.of());
        assertThat(first.add("stored.token")).isTrue();
        Files.writeString(document(), "tokens: [stored.token, from.document]\n");

        assertThat(tokens(Map.of()).readAll()).containsExactly("stored.token", "from.document");
        assertThat(Files.readString(document())).doesNotContain("from.document");
    }

    @Test
    @DisplayName("each server has its own secret")
    void perServer() {
        tokens("smp", Map.of()).add("smp.token");

        assertThat(tokens("arena", Map.of()).readAll()).isEmpty();
        assertThat(tokens("smp", Map.of()).readAll()).containsExactly("smp.token");
    }

    @Test
    @DisplayName("the environment wins over the secret, so a container can keep the token out of the database")
    void environmentWins() {
        BotTokens tokens = tokens(Map.of(BotTokens.ENVIRONMENT, " from.the.env "));
        tokens.add("from.the.database");

        assertThat(tokens.read()).isEqualTo("from.the.env");
        assertThat(tokens.source()).isEqualTo(BotTokens.Source.ENVIRONMENT);
        assertThat(tokens.environmentSet()).isTrue();
    }

    @Test
    @DisplayName("a blank environment variable is ignored rather than read as an empty token")
    void blankEnvironmentIgnored() {
        tokens(Map.of()).add("from.the.database");

        assertThat(tokens(Map.of(BotTokens.ENVIRONMENT, "  ")).read()).isEqualTo("from.the.database");
    }

    @Test
    @DisplayName("the environment can hold several tokens too, comma separated")
    void environmentHoldsSeveral() {
        assertThat(tokens(Map.of(BotTokens.ENVIRONMENT, "main.token, line.one ,line.two")).readAll())
                .containsExactly("main.token", "line.one", "line.two");
    }

    @Test
    @DisplayName("add keeps order and ignores a repeat; clear forgets them all")
    void addAndClear() {
        BotTokens tokens = tokens(Map.of());

        tokens.add("one");
        tokens.add("two");
        tokens.add("one");
        assertThat(tokens.readAll()).containsExactly("one", "two");
        assertThat(tokens.clear()).isTrue();
        assertThat(tokens.readAll()).isEmpty();
    }

    @Test
    @DisplayName("a document that cannot be parsed is an error, not 'no token'")
    void unreadableIsAnError() throws Exception {
        Files.writeString(document(), "tokens: [unclosed\n");

        assertThatThrownBy(() -> tokens(Map.of()).readAll()).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("unclosed");
    }
}
