package de.raindancer.modules.voicebridge;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VoiceBridgeCommandsTest {

    @Test
    @DisplayName("the command can be declared without the module being anywhere near running")
    void declaringNeedsNothingLive() {
        List<ModuleCommand> declared = VoiceBridgeCommands.declared();

        assertThat(declared).hasSize(1);
        assertThat(declared.getFirst().name()).isEqualTo("voicebridge");
        assertThat(declared.getFirst().names()).contains("vb", "discordvoice");
        assertThat(declared.getFirst().options()).contains("join", "leave", "status", "link", "unlink", "groups",
                "group join <name> [password]", "invite <player>", "reconnect");
        assertThat(declared.getFirst().handler()).isNotNull();
    }

    @Test
    @DisplayName("running it while the module is stopped says so rather than throwing a null")
    void aStoppedModuleIsNamed() {
        VoiceBridgeCommands.stopped();

        assertThat(VoiceBridgeCommands.isRunning()).isFalse();
        assertThatThrownBy(() -> VoiceBridgeCommands.declared().getFirst().handler()
                .execute(null, new String[]{"status"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }
}
