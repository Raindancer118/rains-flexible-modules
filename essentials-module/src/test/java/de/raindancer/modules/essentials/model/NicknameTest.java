package de.raindancer.modules.essentials.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NicknameTest {

    @Nested
    @DisplayName("plain text is what a length limit should count")
    class PlainText {

        @Test
        void colourCostsNothingTowardsTheLength() {
            Nickname nickname = Nickname.of("<red>Tom</red>");
            assertThat(nickname.plain()).isEqualTo("Tom");
            assertThat(nickname.length()).isEqualTo(3);
        }

        @Test
        void plainTextWithNoMarkupIsItself() {
            assertThat(Nickname.of("Raindancer").plain()).isEqualTo("Raindancer");
        }
    }

    @Nested
    @DisplayName("blank")
    class Blank {

        @Test
        void emptyIsBlank() {
            assertThat(Nickname.of("").isBlank()).isTrue();
        }

        @Test
        void nullIsBlank() {
            assertThat(Nickname.of(null).isBlank()).isTrue();
        }

        @Test
        void whitespaceOnlyIsBlank() {
            assertThat(Nickname.of("   ").isBlank()).isTrue();
        }

        @Test
        void markupWithNoTextIsBlank() {
            assertThat(Nickname.of("<red></red>").isBlank()).isTrue();
        }
    }

    @Test
    @DisplayName("the raw markup is kept, for rendering, alongside the plain text")
    void rawIsKept() {
        Nickname nickname = Nickname.of("<red>Tom</red>");
        assertThat(nickname.raw()).isEqualTo("<red>Tom</red>");
    }
}
