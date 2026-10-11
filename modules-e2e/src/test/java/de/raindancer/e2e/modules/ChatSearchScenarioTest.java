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
 * Staff search public chat with /chatlog — by player, words, a phrase and today in Berlin time, a page at a time
 * with clickable pages and names.
 */
@Tag("e2e")
class ChatSearchScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("chat is searched by player, words, phrase and day with pages")
    void chatSearch() {
        try (Server server = Server.start("chat-search", List.of("moderation-standalone:RainsModeration-.*"),
                List.of("Moderation is up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");

            for (int i = 0; i < 12; i++) {
                bo.say("rules line " + i);
                Await.ticks(4);
            }
            bo.say("near my base is spawn");
            cy.say("spawn is near my base, read the rules");
            Await.ticks(20);

            ada.forgetChat();
            ada.run("chatlog player:Bo rules");
            Await.until(() -> "the first page of Bo's rules lines (Ada saw " + ada.chatText() + ")", WAIT,
                    () -> said(ada, "rules line 11") && said(ada, "page 1 of 2"));
            assertThat(said(ada, "read the rules")).as("Cy's line is not Bo's").isFalse();
            ada.clickChat("page:2");
            Await.until("the older page", WAIT, () -> said(ada, "rules line 0") && said(ada, "page 2 of 2"));

            ada.forgetChat();
            ada.run("chatlog \"near my base\" day:today");
            Await.until("the phrase, as it stands", WAIT,
                    () -> said(ada, "spawn is near my base") && said(ada, "2 line(s)"));
            assertThat(said(ada, "near my base is spawn")).isTrue();

            ada.clickChat("player:" + cy.id());
            Await.until("clicking Cy's name narrows to Cy, the phrase kept", WAIT,
                    () -> said(ada, "player Cy") && said(ada, "1 line(s)"));

            ada.forgetChat();
            ada.run("chatlog player:Nobody123");
            Await.until("an unknown name is said back", WAIT, () -> said(ada, "Nobody called Nobody123"));
            ada.run("chatlog from:soon");
            Await.until("an unreadable time is said back", WAIT, () -> said(ada, "Could not read from:soon"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
