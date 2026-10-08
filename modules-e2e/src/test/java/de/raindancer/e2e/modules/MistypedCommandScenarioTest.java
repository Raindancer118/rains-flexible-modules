package de.raindancer.e2e.modules;

import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Core's "Did you mean": a mistyped command answered with buttons for the commands close to it. */
@Tag("e2e")
class MistypedCommandScenarioTest {

    @Test
    @DisplayName("a typo gets clickable guesses with its arguments kept, never a command the player may not use")
    void didYouMean() {
        try (Server server = Server.start("mistyped",
                List.of("claims-standalone:RainsExtendedClaims-.*"), List.of("Claims are up"))) {
            Bot bo = server.player("Bo");

            bo.run("hlep");
            Bot.Line bare = bo.expectChat("Did you mean");
            assertThat(bare.text()).contains("/hlep is not a thing here").contains("[/help]");
            assertThat(bare.clicks()).contains("/help");

            bo.forgetChat();
            bo.run("mgs Bo hello there");
            Bot.Line withArgs = bo.expectChat("Did you mean");
            assertThat(withArgs.clicks()).contains("/msg Bo hello there");

            // Not an operator: /gamemode and /mspt are close, but not theirs to be told about.
            bo.forgetChat();
            bo.run("gamemod creative");
            bo.expectChat("command.unknown");
            bo.expectNoChat("gamemode", Duration.ofSeconds(2));
            bo.forgetChat();
            bo.run("msp");
            assertThat(bo.expectChat("Did you mean").text()).contains("[/msg]");
            bo.expectNoChat("mspt", Duration.ofSeconds(2));

            // What clicking the bare guess sends.
            bo.forgetChat();
            bo.run(bare.clicks().getFirst());
            bo.expectChat("Help");

            // A command that exists, with a sub-command typed wrong: the words around it are kept.
            bo.forgetChat();
            bo.run("claim trsut Ada");
            Bot.Line sub = bo.expectChat("Did you mean");
            assertThat(sub.text()).contains("/claim does not do trsut").contains("[/claim trust Ada]");
            assertThat(sub.clicks()).contains("/claim trust Ada");
            bo.expectNoChat("Unknown subcommand", Duration.ofSeconds(1));

            // Nothing close: the module's own answer, as before.
            bo.forgetChat();
            bo.run("claim xyzzyplugh");
            bo.expectChat("Unknown subcommand");
            bo.expectNoChat("Did you mean", Duration.ofSeconds(1));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsExtendedClaims")).isEmpty();
        }
    }
}
