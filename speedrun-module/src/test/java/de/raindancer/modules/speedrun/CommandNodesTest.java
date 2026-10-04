package de.raindancer.modules.speedrun;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.speedrun.manhunt.ManhuntGame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every command declared as needing a node enforces it itself.
 *
 * <p>Found by the end-to-end run: an ordinary player resumed a run with {@code /speedrunresume}. The
 * command was declared {@code needing(ADMIN)} — but that only writes the node into the command book;
 * Paper asks the handler's own {@code permission()}, and the handler had none.
 */
class CommandNodesTest {

    @Test
    @DisplayName("a command's declared node is the node its handler asks for")
    void declaredNodesAreEnforced() {
        List<ModuleCommand> all = new ArrayList<>(SpeedrunCommands.declared());
        all.addAll(ManhuntGame.commands());
        List<String> unenforced = new ArrayList<>();
        for (ModuleCommand command : all) {
            if (command.permission() != null && !command.permission().equals(command.handler().permission())) {
                unenforced.add("/" + command.name() + " needs " + command.permission() + " but its handler asks for "
                        + command.handler().permission());
            }
        }

        assertThat(unenforced).isEmpty();
    }
}
