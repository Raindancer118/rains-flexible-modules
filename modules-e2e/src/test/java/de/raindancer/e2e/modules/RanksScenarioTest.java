package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ranks on a real server beside the real chat and cosmetics plugins: a rank given by the console shows in front
 * of the name in chat, in the tablist and above the head; a player hides it in one place; the server switches a
 * place off; the menu opens; it is all still there after a restart. And Core's server-name setting reaches the
 * tablist header.
 */
@Tag("e2e")
class RanksScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean told(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("give, chat, tablist, nametag, hide per place, server switch, menu, restart, server name")
    void ranks() {
        try (Server server = Server.start("ranks",
                List.of("ranks-standalone:RainsRanks-.*", "chat-standalone:RainsChat-.*",
                        "cosmetics-standalone:RainsCosmetics-.*"),
                List.of("Ranks are up", "Chat is up"))) {
            Bot ada = server.player("Ada");
            Bot bo = server.player("Bo");

            // ---- the server's name, set in the settings, is what the tablist header says
            assertThat(server.console("settings set server-name Lillys Testland")).contains("is now");
            Await.until("the tablist header names the server", WAIT, () -> bo.tabHeader().contains("Lillys Testland"));

            // ---- a rank, given by the console, in front of Ada's name everywhere
            assertThat(server.console("rank give Ada owner")).contains("Ada");
            Await.until("Ada is told", WAIT, () -> told(ada, "[OWNER]"));
            bo.forgetChat();
            ada.say("hello everybody");
            Await.until("[OWNER] is in front of Ada in chat", WAIT, () -> bo.chatText().stream()
                    .anyMatch(line -> line.contains("[OWNER]") && line.contains("Ada") && line.contains("hello everybody")));
            Await.until("[OWNER] is in front of Ada in the tablist", WAIT,
                    () -> bo.tabName(ada.id()).filter(name -> name.contains("[OWNER]") && name.contains("Ada")).isPresent());
            Await.until("[OWNER] floats above Ada's head", WAIT, () -> bo.entityTexts().stream()
                    .anyMatch(text -> text.contains("[OWNER]") && text.contains("Ada")));

            // ---- Ada hides it in chat only
            ada.forgetChat();
            ada.run("rank show chat off");
            Await.until("Ada is told it is hidden", WAIT, () -> told(ada, "hidden"));
            bo.forgetChat();
            ada.say("quietly now");
            Await.until("Bo hears the line", WAIT, () -> told(bo, "quietly now"));
            assertThat(bo.chatText().stream().filter(line -> line.contains("quietly now")))
                    .as("no rank in chat any more").noneMatch(line -> line.contains("[OWNER]"));
            assertThat(bo.tabName(ada.id())).as("still in the tablist").hasValueSatisfying(
                    name -> assertThat(name).contains("[OWNER]"));

            // ---- the server switches the tablist off for everybody
            assertThat(server.console("settings set ranks:show.tablist false")).contains("is now");
            Await.until("the tablist drops it", WAIT,
                    () -> bo.tabName(ada.id()).filter(name -> !name.contains("[OWNER]")).isPresent());

            // ---- the menu
            ada.run("rank");
            ada.awaitWindow("Your rank");
            ada.closeWindow();

            // ---- still there after a restart
            server.paper.restartKeepingBots();
            server.paper.awaitLog("Ranks are up", Duration.ofSeconds(60));
            ada.rejoin();
            bo.rejoin();
            Await.ticks(40);
            bo.forgetChat();
            bo.run("rank info Ada");
            Await.until("Ada still holds it", WAIT, () -> told(bo, "[OWNER]"));
            Await.until("the server name survived the restart", WAIT, () -> bo.tabHeader().contains("Lillys Testland"));

            assertThat(server.paper.errorsFrom("RainsRanks")).as("errors from the ranks plugin").isEmpty();
        }
    }
}
