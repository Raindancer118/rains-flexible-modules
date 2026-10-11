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
 * with clickable pages and names — and see what punished players tried anyway: a muted player talking, a banned
 * one knocking, told as it happens and found with /attempts.
 */
@Tag("e2e")
class ChatSearchScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("chat is searched by player, words, phrase and day with pages; blocked attempts are told and found")
    void chatSearchAndAttempts() {
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

            // A muted player talking: blocked, kept, told.
            ada.run("mute Bo 2h spam");
            Await.until("Bo is muted", WAIT, () -> said(bo, "You have been muted"));
            ada.forgetChat();
            bo.say("let me talk please");
            Await.until(() -> "staff are told (Ada saw " + ada.chatText() + ")", WAIT,
                    () -> said(ada, "Bo tried to talk") && said(ada, "let me talk please"));
            ada.forgetChat();
            bo.say("still muted?");
            Await.ticks(20);
            assertThat(said(ada, "still muted?")).as("the second attempt within the quiet time is held back").isFalse();

            // A banned player knocking.
            cy.leave();
            ada.run("ban Cy griefing");
            Await.until("Cy is banned", WAIT, () -> server.console("banlist players").contains("Cy"));
            ada.forgetChat();
            Bot again = server.paper.bot("Cy");
            assertThat(again.joinRefused()).isNotBlank();
            Await.until(() -> "staff are told Cy knocked (Ada saw " + ada.chatText() + ")", WAIT,
                    () -> said(ada, "Cy tried to join"));

            ada.forgetChat();
            ada.run("attempts type:join");
            Await.until(() -> "the join is found (Ada saw " + ada.chatText() + ")", WAIT, () -> said(ada, "Cy") && said(ada, "tried to join (banned: Griefing)"));
            ada.forgetChat();
            ada.run("attempts player:Bo");
            Await.until("both of Bo's lines are kept", WAIT,
                    () -> said(ada, "let me talk please") && said(ada, "still muted?"));

            ada.run("attempts alerts off");
            Await.until("alerts can be switched off", WAIT, () -> said(ada, "You are not told"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
