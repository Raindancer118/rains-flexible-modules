package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.model.Reason;
import de.raindancer.modules.moderation.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A reason somebody typed, rather than one from the catalogue.
 *
 * <h2>Why this exists</h2>
 * The menus only ever offered the 41 presets, so anything they did not cover had to be done from the
 * command line — which is the half of the plugin a moderator on a phone or in the middle of a fight is
 * least able to reach. The command already accepted free text; the screens did not.
 *
 * <h2>Why it is a Reason and not a bare string</h2>
 * Because everything downstream — the duration menu, the confirmation, the record — takes a Reason, and
 * threading a nullable string beside it through all three is how one of them ends up showing "null" to
 * a player. It is a Reason that happens to count towards nothing.
 */
class CustomReasonTest {

    @Test
    @DisplayName("what somebody typed becomes the label")
    void theLabel() {
        Reason typed = Reason.typedByHand(PunishmentKind.BAN, "building a lag machine");

        assertThat(typed.label()).isEqualTo("building a lag machine");
        assertThat(typed.kind()).isEqualTo(PunishmentKind.BAN);
    }

    @Test
    @DisplayName("it never climbs a ladder")
    void noLadder() {
        // A typed reason cannot be counted against a previous one, because nothing matches it: two
        // people typing "griefing" and "Griefing " are not obviously the same offence, and treating
        // them as one would make the ladder lie in the direction that punishes harder.
        Reason typed = Reason.typedByHand(PunishmentKind.MUTE, "whatever");

        assertThat(typed.escalates()).isFalse();
        assertThat(typed.at(0)).isEqualTo(typed.at(9));
    }

    @Test
    @DisplayName("it is marked as typed, so a screen can say so")
    void marked() {
        assertThat(Reason.typedByHand(PunishmentKind.KICK, "x").isTypedByHand()).isTrue();
        assertThat(new Reason("spam", "Spam", PunishmentKind.MUTE, Severity.MINOR,
                java.util.List.of(de.raindancer.modules.moderation.model.Sentence.forEver()))
                .isTypedByHand()).isFalse();
    }

    @Test
    @DisplayName("blank text is refused rather than becoming an empty reason")
    void blank() {
        // "no reason given" is a decision somebody makes; an empty string is a mistake, and a
        // punishment whose reason is "" is one nobody can explain later.
        assertThatThrownBy(() -> Reason.typedByHand(PunishmentKind.BAN, "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Reason.typedByHand(PunishmentKind.BAN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an over-long reason is cut rather than refused")
    void tooLong() {
        // Refusing it would throw away what they typed after they had typed it. Cutting keeps the
        // beginning, which is the part that says what happened.
        Reason typed = Reason.typedByHand(PunishmentKind.BAN, "g".repeat(500));

        assertThat(typed.label().length()).isLessThanOrEqualTo(Reason.LONGEST_TYPED);
        assertThat(typed.label()).startsWith("ggg");
    }
}
