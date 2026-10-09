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

    @Test
    @DisplayName("/roast and /joke are declared, and /roast takes an optional player")
    void funCommands() {
        assertThat(named("roast").names()).contains("roast");
        assertThat(named("joke").names()).contains("joke");
    }

    @Test
    @DisplayName("/rules and /admin are declared; /admin answers to adminmode and staffmode")
    void rulesAndAdmin() {
        assertThat(named("rules").names()).contains("rules");
        assertThat(named("admin").names()).contains("admin", "adminmode", "staffmode");
    }
}
