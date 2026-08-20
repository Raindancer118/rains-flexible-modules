package de.raindancer.modules.xaeromap;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.xaeromap.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** What the module declares at bootstrap, and the state it must survive: registered before it runs. */
class XaeroMapCommandsTest {

    @Test
    @DisplayName("there is exactly one command, and it is /xaeromap")
    void oneCommand() {
        List<ModuleCommand> declared = XaeroMapCommands.declared();

        assertThat(declared).hasSize(1);
        assertThat(declared.getFirst().name()).isEqualTo("xaeromap");
    }

    @Test
    @DisplayName("it can be declared before anything is built")
    void nothingIsCapturedAtBootstrap() {
        assertThat(XaeroMapCommands.isRunning())
                .as("Paper asks for commands during bootstrap, before enable — a handler that "
                        + "captured the services then would capture nothing")
                .isFalse();
        assertThatCode(XaeroMapCommands::declared).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("running it before the module starts throws rather than doing nothing")
    void itRefusesRatherThanReturningNull() {
        ModuleCommand declared = XaeroMapCommands.declared().getFirst();

        assertThatCode(() -> declared.handler().execute(null, new String[0]))
                .as("the host wraps this in ModuleCommands.guarded, which turns it into a refusal "
                        + "a player can read rather than a stack trace")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }

    @Test
    @DisplayName("the command itself is open; the staff half checks its own node")
    void theBareCommandIsForEverybody() {
        assertThat(XaeroMapCommands.declared().getFirst().permission())
                .as("bare /xaeromap is a player fixing their own map, which costs one resync of "
                        + "one player — gating it means they ask an admin instead")
                .isEqualTo(PermissionNodes.REFRESH);
    }

    @Test
    @DisplayName("it offers its subcommands as usage, so /xaeromap ? is not the only way to find them")
    void theUsageNamesTheSubcommands() {
        assertThat(XaeroMapCommands.declared().getFirst().options())
                .anyMatch(option -> option.contains("homes") && option.contains("warps")
                        && option.contains("status"));
    }

    @Test
    @DisplayName("it is worth a line in the audit journal")
    void isAudited() {
        assertThat(XaeroMapCommands.declared().getFirst().audited()).isTrue();
    }
}
