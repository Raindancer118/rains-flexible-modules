package de.raindancer.modules.chat.store;

import de.raindancer.modules.chat.model.ChatStyle;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ChatStyleStoreTest {

    private final Path dataFolder = Path.of("target", "test-chat-style");
    private ChatStyleStore store;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(dataFolder);
        store = new ChatStyleStore(dataFolder);
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> files = Files.walk(dataFolder)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        }
    }

    @Test
    @DisplayName("somebody who has never chosen anything gets DEFAULT")
    void unknownPlayerGetsDefault() {
        assertThat(store.of(UUID.randomUUID())).isEqualTo(ChatStyle.DEFAULT);
    }

    @Test
    @DisplayName("a chosen style survives a save and a fresh load")
    void survivesReload() {
        UUID tom = UUID.randomUUID();
        ChatStyle chosen = new ChatStyle(NamedTextColor.GOLD, true, false, true, false);

        store.set(tom, chosen);
        ChatStyleStore reloaded = new ChatStyleStore(dataFolder);
        reloaded.load();

        assertThat(reloaded.of(tom)).isEqualTo(chosen);
    }

    @Test
    @DisplayName("setting DEFAULT removes them from the file rather than writing an empty entry")
    void settingDefaultForgetsThem() {
        UUID tom = UUID.randomUUID();
        store.set(tom, new ChatStyle(NamedTextColor.GOLD, false, false, false, false));

        store.set(tom, ChatStyle.DEFAULT);
        ChatStyleStore reloaded = new ChatStyleStore(dataFolder);
        reloaded.load();

        assertThat(reloaded.of(tom)).isEqualTo(ChatStyle.DEFAULT);
        assertThat(reloaded.tracked()).isZero();
    }

    @Test
    @DisplayName("only somebody who has chosen something is counted")
    void trackedCountsOnlyChosen() {
        store.set(UUID.randomUUID(), new ChatStyle(NamedTextColor.RED, false, false, false, false));
        store.set(UUID.randomUUID(), ChatStyle.DEFAULT);

        assertThat(store.tracked()).isEqualTo(1);
    }

    @Test
    @DisplayName("a hand-edited line with no colour still loads its decorations")
    void loadsDecorationsWithNoColor() throws IOException {
        UUID tom = UUID.randomUUID();
        Files.writeString(dataFolder.resolve("chatstyles.yml"),
                "players:\n  " + tom + ":\n    bold: true\n    underlined: true\n");

        store.load();

        ChatStyle loaded = store.of(tom);
        assertThat(loaded.color()).isNull();
        assertThat(loaded.bold()).isTrue();
        assertThat(loaded.underlined()).isTrue();
        assertThat(loaded.has(TextDecoration.ITALIC)).isFalse();
    }

    @Test
    @DisplayName("a hand-edited unrecognised colour name is read as none chosen, not a crash")
    void unrecognisedColorNameIsIgnored() throws IOException {
        UUID tom = UUID.randomUUID();
        Files.writeString(dataFolder.resolve("chatstyles.yml"),
                "players:\n  " + tom + ":\n    color: notacolour\n    bold: true\n");

        store.load();

        assertThat(store.of(tom).color()).isNull();
        assertThat(store.of(tom).bold()).isTrue();
    }
}
