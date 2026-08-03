package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.Sentence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How long a punishment is for.
 *
 * <h2>Why this is a type rather than a nullable Duration</h2>
 * Because "for ever" and "unreadable" are both {@code null} when a length is parsed into a
 * {@link Duration}, and every moderation plugin that has ever conflated the two has at some point
 * banned somebody permanently for typing {@code 2 hours} with a space in it. {@link Sentence#parse}
 * gives back an empty {@link java.util.Optional} for the typo and a permanent sentence only for the
 * words that mean permanent.
 */
class SentenceTest {

    @Nested
    @DisplayName("building one")
    class Building {

        @Test
        @DisplayName("a length is kept as it was given")
        void aLengthIsKept() {
            Sentence two = Sentence.of(Duration.ofHours(2));

            assertThat(two.isPermanent()).isFalse();
            assertThat(two.length()).contains(Duration.ofHours(2));
        }

        @Test
        @DisplayName("for ever has no length at all")
        void forEverHasNoLength() {
            Sentence never = Sentence.forEver();

            assertThat(never.isPermanent()).isTrue();
            assertThat(never.length()).isEmpty();
        }

        @Test
        @DisplayName("a zero or negative length is refused rather than silently made permanent")
        void nonsenseIsRefused() {
            assertThatThrownBy(() -> Sentence.of(Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Sentence.of(Duration.ofSeconds(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> Sentence.of(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("reading what somebody typed")
    class Parsing {

        @Test
        @DisplayName("a length somebody types")
        void aLength() {
            assertThat(Sentence.parse("2h")).hasValueSatisfying(
                    sentence -> assertThat(sentence.length()).contains(Duration.ofHours(2)));
            assertThat(Sentence.parse("30m")).hasValueSatisfying(
                    sentence -> assertThat(sentence.length()).contains(Duration.ofMinutes(30)));
            assertThat(Sentence.parse("7d")).hasValueSatisfying(
                    sentence -> assertThat(sentence.length()).contains(Duration.ofDays(7)));
        }

        @Test
        @DisplayName("the words that mean for ever")
        void forEver() {
            for (String word : new String[]{"perm", "permanent", "forever", "PERM"}) {
                assertThat(Sentence.parse(word))
                        .as("'%s' should mean permanent", word)
                        .hasValueSatisfying(sentence -> assertThat(sentence.isPermanent()).isTrue());
            }
        }

        @Test
        @DisplayName("something unreadable is empty, and is never mistaken for for ever")
        void nonsenseIsEmpty() {
            // The whole reason this type exists. A typo that parsed as permanent is a permanent ban
            // nobody meant to hand out.
            assertThat(Sentence.parse("two hours")).isEmpty();
            assertThat(Sentence.parse("")).isEmpty();
            assertThat(Sentence.parse(null)).isEmpty();
            assertThat(Sentence.parse("   ")).isEmpty();
        }
    }

    @Nested
    @DisplayName("when it ends")
    class Ending {

        private final Instant noon = Instant.parse("2026-08-03T12:00:00Z");

        @Test
        @DisplayName("a length counts forward from now")
        void aLengthCountsForward() {
            assertThat(Sentence.of(Duration.ofHours(3)).endingAt(noon))
                    .isEqualTo(Instant.parse("2026-08-03T15:00:00Z"));
        }

        @Test
        @DisplayName("for ever never ends, which is what null means to Core")
        void forEverIsNull() {
            // Punishments.punish takes a null length for a punishment that never ends, so this is the
            // one place the module is allowed to produce one — and it does it deliberately.
            assertThat(Sentence.forEver().endingAt(noon)).isNull();
            assertThat(Sentence.forEver().orNull()).isNull();
            assertThat(Sentence.of(Duration.ofHours(3)).orNull()).isEqualTo(Duration.ofHours(3));
        }

        @Test
        @DisplayName("it says how long it is in words somebody would use")
        void itDescribesItself() {
            assertThat(Sentence.forEver().describe()).isEqualTo("for ever");
            assertThat(Sentence.of(Duration.ofHours(2)).describe()).isNotBlank()
                    .doesNotContain("PT");
        }
    }
}
