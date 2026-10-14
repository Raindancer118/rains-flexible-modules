package de.raindancer.modules.voicebridge.store;

import de.raindancer.core.data.store.YamlStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BotTokensTest {

    @TempDir
    Path folder;

    private final Path old() {
        return folder.resolve("discord-token.txt");
    }

    private BotTokens tokens(Map<String, String> env) {
        return new BotTokens(new YamlStore(folder.resolve("discord-tokens.yml")), old(), env::get);
    }

    @Test
    @DisplayName("nothing anywhere leaves an explained template, and reads as no token")
    void writesTemplate() throws Exception {
        BotTokens tokens = tokens(Map.of());

        assertThat(tokens.read()).isEmpty();
        assertThat(Files.readString(folder.resolve("discord-tokens.yml"))).contains("#").contains("tokens: []");
        assertThat(tokens.source()).isEqualTo(BotTokens.Source.NONE);
        assertThat(old()).doesNotExist();
    }

    @Test
    @DisplayName("the 1.x file is brought in once, comments and blanks skipped, tokens trimmed, and kept renamed")
    void importsTheOldFile() throws Exception {
        Files.writeString(old(), "# main\nmain.token\n\n# lines\n  line.one \nline.two\n");
        BotTokens tokens = tokens(Map.of());

        assertThat(tokens.readAll()).containsExactly("main.token", "line.one", "line.two");
        assertThat(tokens.source()).isEqualTo(BotTokens.Source.DOCUMENT);
        assertThat(old()).doesNotExist();
        assertThat(folder.resolve("discord-token.txt.imported-2.0.0")).exists();
        assertThat(tokens(Map.of()).readAll()).as("from the document now").containsExactly("main.token", "line.one", "line.two");
    }

    @Test
    @DisplayName("tokens put into the document are read in order")
    void readsTheDocument() throws Exception {
        Files.writeString(folder.resolve("discord-tokens.yml"), "tokens:\n  - ' main.token '\n  - line.one\n");
        BotTokens tokens = tokens(Map.of());

        assertThat(tokens.read()).isEqualTo("main.token");
        assertThat(tokens.readAll()).containsExactly("main.token", "line.one");
    }

    @Test
    @DisplayName("the environment wins over the document, so a container can keep the token out of the database")
    void environmentWins() throws Exception {
        Files.writeString(folder.resolve("discord-tokens.yml"), "tokens: [from.the.document]\n");
        BotTokens tokens = tokens(Map.of(BotTokens.ENVIRONMENT, " from.the.env "));

        assertThat(tokens.read()).isEqualTo("from.the.env");
        assertThat(tokens.source()).isEqualTo(BotTokens.Source.ENVIRONMENT);
    }

    @Test
    @DisplayName("a blank environment variable is ignored rather than read as an empty token")
    void blankEnvironmentIgnored() throws Exception {
        Files.writeString(folder.resolve("discord-tokens.yml"), "tokens: [from.the.document]\n");

        assertThat(tokens(Map.of(BotTokens.ENVIRONMENT, "  ")).read()).isEqualTo("from.the.document");
    }

    @Test
    @DisplayName("the environment can hold several tokens too, comma separated")
    void environmentHoldsSeveral() {
        assertThat(tokens(Map.of(BotTokens.ENVIRONMENT, "main.token, line.one ,line.two")).readAll())
                .containsExactly("main.token", "line.one", "line.two");
    }

    @Test
    @DisplayName("a document that cannot be parsed is an error, not 'no token'")
    void unreadableIsAnError() throws Exception {
        Files.writeString(folder.resolve("discord-tokens.yml"), "tokens: [unclosed\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> tokens(Map.of()).readAll())
                .isInstanceOf(IllegalStateException.class);
    }
}
