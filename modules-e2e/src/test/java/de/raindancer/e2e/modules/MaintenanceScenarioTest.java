package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Core's /maintenance on a real server: who is let in, who is sent off, and that it outlives a restart of the mode. */
@Tag("e2e")
class MaintenanceScenarioTest {

    @Test
    @DisplayName("during maintenance only ops and the maintenance list get in; everybody else is sent off and turned away")
    void maintenance() {
        try (Server server = Server.start("maintenance", List.of("chat-standalone:RainsChat-.*"), List.of("Chat is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            // Known to the server first: a name it never saw would be asked of Mojang, whose id is not the bot's.
            Bot cy = server.player("Cy");
            cy.leave();

            ada.runAndExpect("maintenance add Cy", "Cy is on the maintenance list");
            ada.runAndExpect("maintenance on New spawn", "Maintenance mode is on");

            Await.until("Bo is sent off", Duration.ofSeconds(10), () -> !bo.isOnline());
            assertThat(bo.disconnectReason()).contains("under maintenance").contains("New spawn");
            assertThat(ada.isOnline()).as("an op stays").isTrue();

            assertThat(server.paper.bot("Dee").joinRefused()).contains("under maintenance");
            cy.rejoin();
            assertThat(cy.isOnline()).as("on the list").isTrue();

            ada.runAndExpect("wartung", "Maintenance mode is on");
            ada.runAndExpect("maintenance remove Cy", "Cy is off the maintenance list");
            Await.until("Cy is sent off once off the list", Duration.ofSeconds(10), () -> !cy.isOnline());

            ada.runAndExpect("maintenance off", "Maintenance mode is off");
            assertThat(server.paper.bot("Dee").join().isOnline()).isTrue();
            assertThat(bo.rejoin().isOnline()).isTrue();

            bo.answer(() -> bo.run("maintenance on"),
                    answer -> answer.says("Unknown or incomplete command") || answer.says("command.unknown.command"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsChat")).isEmpty();
        }
    }
}
