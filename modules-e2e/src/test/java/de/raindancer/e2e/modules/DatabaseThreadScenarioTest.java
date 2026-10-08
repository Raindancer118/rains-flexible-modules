package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Core reports every database read or write made on a thread running the world. Setting and deleting
 * homes and reading the audit journal — by command and as a menu — must not give it anything to report.
 */
@Tag("e2e")
class DatabaseThreadScenarioTest {

    @Test
    @DisplayName("homes and the audit journal keep the database off the world's thread")
    void noDatabaseOnTheWorldThread() {
        try (Server server = Server.start("database-threads",
                List.of("homes-standalone:RainsHomes-.*", "moderation-standalone:RainsModeration-.*"),
                List.of("Moderation"))) {
            Bot ada = server.admin("Ada");

            ada.runAndExpect("sethome base", "base");
            ada.runAndExpect("sethome mine", "mine");
            ada.runAndExpect("delhome mine", "mine");
            Await.ticks(40);

            ada.runAndExpect("audit", "");
            ada.runAndExpect("audit Ada", "");
            ada.run("mod audit");
            ada.awaitWindow("Audit");
            Await.ticks(40);

            assertThat(server.paper.logLines(line -> line.contains("thread running the world")))
                    .as("database work on the world's thread").isEmpty();
            assertThat(server.paper.errorsFrom("RainsCore", "RainsHomes", "RainsModeration")).isEmpty();
        }
    }
}
