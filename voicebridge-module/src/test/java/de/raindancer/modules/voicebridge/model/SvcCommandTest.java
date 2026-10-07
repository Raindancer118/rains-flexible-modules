package de.raindancer.modules.voicebridge.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SvcCommandTest {

    @Test
    @DisplayName("SVC's commands are recognised, namespaced or not, any case")
    void recognises() {
        assertThat(SvcCommand.parse("/voicechat join Builders")).hasValueSatisfying(command -> {
            assertThat(command.sub()).isEqualTo("join");
            assertThat(command.args()).containsExactly("Builders");
        });
        assertThat(SvcCommand.parse("/voicechat:voicechat LEAVE")).hasValueSatisfying(
                command -> assertThat(command.sub()).isEqualTo("leave"));
    }

    @Test
    @DisplayName("a quoted password stays one argument, as SVC's invite writes it")
    void quotes() {
        assertThat(SvcCommand.parse("/voicechat join 5f0c-id \"two words\"")).hasValueSatisfying(
                command -> assertThat(command.args()).containsExactly("5f0c-id", "two words"));
    }

    @Test
    @DisplayName("other commands, and /voicechat on its own, are none of this module's business")
    void ignores() {
        assertThat(SvcCommand.parse("/voicechatter join x")).isEmpty();
        assertThat(SvcCommand.parse("/msg voicechat join")).isEmpty();
        assertThat(SvcCommand.parse("/voicechat")).isEmpty();
        assertThat(SvcCommand.parse("")).isEmpty();
    }
}
