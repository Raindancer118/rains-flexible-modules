package de.raindancer.e2e.modules;

import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** /poll in RainsChat on a real server: buttons, changing a vote, results, ending early, the menu. */
@Tag("e2e")
class PollScenarioTest {

    @Test
    @DisplayName("a poll is started, voted on by clicking, changed, ended early, and won")
    void poll() {
        try (Server server = Server.start("poll", List.of("chat-standalone:RainsChat-.*"), List.of("Chat is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");

            ada.runAndOpen("poll", "New poll");
            ada.closeWindow();

            ada.runAndExpect("poll 5m Best mob? | Creeper | Axolotl", "Best mob?");
            bo.answer(() -> bo.clickButtonOn("Creeper", 0), answer -> answer.says("Vote's in"));
            bo.answer(() -> bo.clickButtonOn("Creeper", 1), answer -> answer.says("Vote changed"));
            bo.answer(() -> bo.clickButtonOn("Creeper", 1), answer -> answer.says("You already picked"));
            cy.answer(() -> cy.clickButtonOn("Creeper", 1), answer -> answer.says("Vote's in"));

            ada.runAndExpect("poll Another? | a | b", "Only one poll can run at a time");
            ada.runAndExpect("poll results", "So far");
            bo.answer(() -> bo.run("poll Can I? | yes | no"),
                    answer -> answer.says("Unknown or incomplete command") || answer.says("command.unknown.command"));

            ada.answer(() -> ada.run("poll end"), answer -> answer.says("Winner: Axolotl"));
            bo.answer(() -> bo.clickButtonOn("Creeper", 0), answer -> answer.says("That poll is over"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsChat")).isEmpty();
        }
    }
}
