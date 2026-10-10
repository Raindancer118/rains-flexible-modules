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
            // Called off during the countdown: nobody goes.
            ada.runAndExpect("maintenance on", "sent off in 20 s");
            bo.expectChat("Maintenance starts in 20 s");
            ada.runAndExpect("maintenance off", "Maintenance mode is off");
            bo.expectChat("Maintenance was called off");
            Await.ticks(20 * 22);
            assertThat(bo.isOnline()).as("called off, so Bo stays").isTrue();

            ada.runAndExpect("maintenance on New spawn", "sent off in 20 s");
            bo.expectChat("starts in 20 s. (New spawn)");
            ada.expectChat("(New spawn) You can stay");
            assertThat(server.paper.bot("Dee").joinRefused()).as("closed to joins during the countdown").contains("under maintenance");
            assertThat(bo.isOnline()).as("not before the countdown ends").isTrue();
            // 17 s into the countdown: longer than expectChat waits.
            Await.until("Bo is told 3 s are left", Duration.ofSeconds(25),
                    () -> bo.chatText().stream().anyMatch(line -> line.contains("starts in 3 s. (New spawn)")));

            Await.until("Bo is sent off", Duration.ofSeconds(15), () -> !bo.isOnline());
            assertThat(bo.disconnectReason()).contains("under maintenance").contains("New spawn");
            assertThat(ada.isOnline()).as("an op stays").isTrue();

            cy.rejoin();
            assertThat(cy.isOnline()).as("on the list").isTrue();

            ada.runAndExpect("wartung", "Maintenance mode is on");
            ada.runAndExpect("maintenance remove Cy", "Cy is off the maintenance list");
            Await.until("Cy is sent off once off the list", Duration.ofSeconds(10), () -> !cy.isOnline());

            ada.runAndExpect("maintenance off", "Maintenance mode is off");
            assertThat(server.paper.bot("Dee").join().isOnline()).isTrue();
            assertThat(bo.rejoin().isOnline()).isTrue();

            // The update wording, as somebody turned away reads it.
            ada.runAndExpect("maintenance on update 3", "sent off in 60 s");
            bo.expectChat("starts in 60 s. (We're updating; back in about 3 minutes)");
            assertThat(server.paper.bot("Eve").joinRefused())
                    .contains("Hey you! We're updating the server and expect to be back in about 3 minutes. Please try again then!");
            ada.runAndExpect("maintenance off", "Maintenance mode is off");

            bo.answer(() -> bo.run("maintenance on"),
                    answer -> answer.says("Unknown or incomplete command") || answer.says("command.unknown.command"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsChat")).isEmpty();
        }
    }
}
