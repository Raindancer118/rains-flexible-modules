package de.raindancer.modules.essentials;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The roasts and the jokes are said in chat as whoever asked, so they are plain text: markup would be
 * typed out literally. And there have to be enough of them that a server does not hear the same one
 * twice in an evening.
 */
class FunLinesTest {

    private static final YamlConfiguration WORDING = YamlConfiguration.loadConfiguration(
            new File("src/main/resources/de/raindancer/modules/essentials/messages.yml"));

    private static List<String> list(String key) {
        return WORDING.getStringList(key);
    }

    @Test
    @DisplayName("at least sixty roasts, every one naming its target, none twice")
    void roasts() {
        List<String> roasts = list("essentials.fun.roasts");
        assertThat(roasts).hasSizeGreaterThanOrEqualTo(60);
        assertThat(roasts).allMatch(line -> line.contains("<target>"), "names <target>");
        assertThat(new HashSet<>(roasts.stream().map(line -> line.toLowerCase(Locale.ROOT)).toList()))
                .hasSameSizeAs(roasts);
    }

    @Test
    @DisplayName("at least sixty jokes, none twice, none about anybody in particular")
    void jokes() {
        List<String> jokes = list("essentials.fun.jokes");
        assertThat(jokes).hasSizeGreaterThanOrEqualTo(60);
        assertThat(jokes).noneMatch(line -> line.contains("<target>"));
        assertThat(new HashSet<>(jokes.stream().map(line -> line.toLowerCase(Locale.ROOT)).toList()))
                .hasSameSizeAs(jokes);
    }

    @Test
    @DisplayName("plain text only, and short enough for one chat message")
    void plainAndShort() {
        for (String line : concat(list("essentials.fun.roasts"), list("essentials.fun.jokes"))) {
            assertThat(line.replace("<target>", "")).as(line).doesNotContain("<").doesNotContain(">");
            // 256 is the chat limit; a 16-character name in place of <target> must still fit.
            assertThat(line.replace("<target>", "x".repeat(16)).length()).as(line).isLessThanOrEqualTo(256);
            assertThat(line).as(line).doesNotStartWith("/");
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
