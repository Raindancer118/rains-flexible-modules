package de.raindancer.modules.essentials;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EssentialsCommandsTest {

    private static ModuleCommand named(String name) {
        return EssentialsCommands.declared().stream()
                .filter(command -> command.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("/" + name + " is not declared"));
    }

    @Test
    @DisplayName("/msg answers to /m, and leaves /w to World Utils' world switch")
    void msgAliases() {
        // /w belongs to RainsWorldUtils. Two plugins registering it leaves Paper to pick one, so on a
        // server with both, half the time /w whispered and half the time it switched worlds.
        assertThat(named("msg").names())
                .contains("msg", "m", "tell", "whisper")
                .doesNotContain("w");
    }
}
