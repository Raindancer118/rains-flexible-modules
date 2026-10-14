package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Twitch advancements on a real server, driven by staff's test announcement: a plain test earns nothing,
 * one that "counts" does — tuned in, a late joiner's hype train, a death near the streamer, and at the end the
 * afterparty for all and day one only for whoever was there from the start.
 */
@Tag("e2e")
class TwitchAdvancementsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean done(Server server, String player, String advancement) {
        return server.console("execute if entity @a[name=" + player + ",advancements={rainstwitch:" + advancement
                + "=true}]").contains("passed");
    }

    @Test
    @DisplayName("tuned in, hype train, clip that, afterparty and day one — and a mere test earns nothing")
    void advancements() {
        try (Server server = Server.start("twitch-advancements", List.of("twitch-standalone:RainsTwitch-.*"),
                List.of(), Map.of("plugins/RainsTwitch/config.yml", "streamer-minecraft-name: Bo\nnear-blocks: 16\n"),
                List.of("Twitch announcements are up"))) {
            Bot ada = server.player("Ada");
            Bot bo = server.player("Bo");
            server.console("gamemode survival Ada");
            Await.ticks(20);

            // --- a staff test is shown, and earns nothing ------------------------------------------------
            server.console("twitch test live all");
            Await.ticks(40);
            assertThat(done(server, "Ada", "tuned_in")).as("a test is not a stream").isFalse();
            server.console("twitch test ended all");
            Await.ticks(20);

            // --- one that counts: everybody online is tuned in ---------------------------------------------
            server.console("twitch test live all counts");
            Await.until("Ada is tuned in", WAIT, () -> done(server, "Ada", "tuned_in"));
            assertThat(done(server, "Ada", "root")).isTrue();

            // --- joining now rides the hype train --------------------------------------------------------
            Bot cy = server.player("Cy");
            Await.until("Cy is on the hype train", WAIT, () -> done(server, "Cy", "hype_train"));
            assertThat(done(server, "Cy", "tuned_in")).as("not there when it went live").isFalse();

            // --- dying next to the streamer: clip that ----------------------------------------------------
            server.console("tp Ada 0.5 100 0.5");
            server.console("tp Bo 2.5 100 0.5");
            Await.ticks(10);
            server.console("kill Ada");
            Await.until("clip that", WAIT, () -> done(server, "Ada", "clip_that"));
            ada.respawn();

            // --- the end: afterparty for all, day one only for those there from the start -----------------
            server.console("twitch test ended all counts");
            Await.until("everybody's afterparty", WAIT, () -> done(server, "Ada", "afterparty")
                    && done(server, "Bo", "afterparty") && done(server, "Cy", "afterparty"));
            Await.until("day one for Ada and Bo", WAIT, () -> done(server, "Ada", "day_one") && done(server, "Bo", "day_one"));
            assertThat(done(server, "Cy", "day_one")).as("Cy came late").isFalse();
            assertThat(bo.isOnline()).isTrue();

            assertThat(server.paper.errorsFrom("RainsCore", "RainsTwitch")).isEmpty();
        }
    }
}
