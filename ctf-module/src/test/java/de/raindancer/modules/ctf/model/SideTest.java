package de.raindancer.modules.ctf.model;

import de.raindancer.core.social.team.TeamColour;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("a side's name in colour")
class SideTest {

    @Test
    @DisplayName("a guild's own name is shown as written, never read as markup")
    void escaped() {
        Side side = new Side(0, TeamColour.RED, "<click:run_command:/op me>Evil</click>", 0, "g");
        var shown = MiniMessage.miniMessage().deserialize(side.tagged());
        assertThat(PlainTextComponentSerializer.plainText().serialize(shown))
                .isEqualTo("<click:run_command:/op me>Evil</click>");
        assertThat(shown.clickEvent()).isNull();
        assertThat(shown.children()).allSatisfy(child -> assertThat(child.clickEvent()).isNull());
    }
}
