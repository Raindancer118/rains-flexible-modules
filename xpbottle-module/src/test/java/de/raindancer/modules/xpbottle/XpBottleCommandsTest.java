package de.raindancer.modules.xpbottle;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the module declares at bootstrap, and how it behaves before it is running — the state a
 * player reaches three ways: before it starts, after it failed, and after it stopped.
 */
class XpBottleCommandsTest {

    @Test
    @DisplayName("the command can be declared without the module being anywhere near running")
    void declaringNeedsNothingLive() {
        List<ModuleCommand> declared = XpBottleCommands.declared();

        assertThat(declared).hasSize(1);
        assertThat(declared.getFirst().name()).isEqualTo("xpbottle");
        assertThat(declared.getFirst().names()).contains("xpb");
        assertThat(declared.getFirst().options()).contains("give <player> [tier]");
    }

    @Test
    @DisplayName("the handler exists before the module does, and holds nothing from it")
    void theHandlerIsBuiltEarly() {
        assertThat(XpBottleCommands.declared().getFirst().handler()).isNotNull();
    }

    @Test
    @DisplayName("running it while the module is stopped says so rather than throwing a null")
    void aStoppedModuleIsNamed() {
        XpBottleCommands.stopped();

        assertThat(XpBottleCommands.isRunning()).isFalse();
        assertThatThrownBy(() -> XpBottleCommands.declared().getFirst().handler()
                .execute(null, new String[]{"give", "somebody"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }
}
