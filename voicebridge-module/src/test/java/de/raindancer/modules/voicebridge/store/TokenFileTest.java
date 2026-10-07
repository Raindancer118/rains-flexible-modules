package de.raindancer.modules.voicebridge.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TokenFileTest {

    @TempDir
    Path folder;

    private TokenFile file(Map<String, String> env) {
        return new TokenFile(folder.resolve("discord-token.txt"), env::get);
    }

    @Test
    @DisplayName("a missing file is written as an explained template, and reads as no token")
    void writesTemplate() throws Exception {
        TokenFile tokens = file(Map.of());

        assertThat(tokens.read()).isEmpty();
        Path written = folder.resolve("discord-token.txt");
        assertThat(written).exists();
        assertThat(Files.readString(written)).contains("#");
        assertThat(tokens.source()).isEqualTo(TokenFile.Source.NONE);
    }

    @Test
    @DisplayName("the template is only readable by the server's own user")
    void templateIsPrivate() throws Exception {
        file(Map.of()).read();

        Path written = folder.resolve("discord-token.txt");
        if (written.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(Files.getPosixFilePermissions(written))
                    .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        }
    }

    @Test
    @DisplayName("comment lines and blank lines are skipped, the token is trimmed")
    void readsTheToken() throws Exception {
        Files.writeString(folder.resolve("discord-token.txt"), "# a comment\n\n   abc.def.ghi  \n# more\n");
        TokenFile tokens = file(Map.of());

        assertThat(tokens.read()).isEqualTo("abc.def.ghi");
        assertThat(tokens.source()).isEqualTo(TokenFile.Source.FILE);
    }

    @Test
    @DisplayName("an existing file is never overwritten by the template")
    void keepsTheFile() throws Exception {
        Files.writeString(folder.resolve("discord-token.txt"), "abc.def.ghi\n");
        file(Map.of()).read();

        assertThat(Files.readString(folder.resolve("discord-token.txt"))).isEqualTo("abc.def.ghi\n");
    }

    @Test
    @DisplayName("the environment wins over the file, so a container can keep the token out of the volume")
    void environmentWins() throws Exception {
        Files.writeString(folder.resolve("discord-token.txt"), "from.the.file\n");
        TokenFile tokens = file(Map.of(TokenFile.ENVIRONMENT, " from.the.env "));

        assertThat(tokens.read()).isEqualTo("from.the.env");
        assertThat(tokens.source()).isEqualTo(TokenFile.Source.ENVIRONMENT);
    }

    @Test
    @DisplayName("a blank environment variable is ignored rather than read as an empty token")
    void blankEnvironmentIgnored() throws Exception {
        Files.writeString(folder.resolve("discord-token.txt"), "from.the.file\n");

        assertThat(file(Map.of(TokenFile.ENVIRONMENT, "  ")).read()).isEqualTo("from.the.file");
    }

    @Test
    @DisplayName("every token line is read in order: the first is the main bot, the rest are proximity lines")
    void readsAll() throws Exception {
        Files.writeString(folder.resolve("discord-token.txt"), "# main\nmain.token\n\n# lines\nline.one\nline.two\n");
        TokenFile tokens = file(Map.of());

        assertThat(tokens.readAll()).containsExactly("main.token", "line.one", "line.two");
        assertThat(tokens.read()).isEqualTo("main.token");
    }

    @Test
    @DisplayName("the environment can hold several tokens too, comma separated")
    void environmentHoldsSeveral() throws Exception {
        TokenFile tokens = file(Map.of(TokenFile.ENVIRONMENT, "main.token, line.one ,line.two"));

        assertThat(tokens.readAll()).containsExactly("main.token", "line.one", "line.two");
    }
}
