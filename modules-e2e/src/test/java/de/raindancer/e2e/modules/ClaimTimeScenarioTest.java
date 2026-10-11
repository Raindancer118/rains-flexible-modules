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

/** A claim's own time of day, set to the tick on Core's picker: back and forth, round midnight, kept on Accept. */
@Tag("e2e")
class ClaimTimeScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static void expect(Bot bot, String what, String text) {
        try {
            Await.until(what, WAIT, () -> said(bot, text));
        } catch (AssertionError failed) {
            throw new AssertionError(failed.getMessage() + " — " + bot.name() + " saw " + bot.chatText(), failed);
        }
    }

    private static int slot(Bot bot, String text) {
        return Await.value("a button named " + text, WAIT, () -> bot.window().flatMap(window -> window.top()
                .entrySet().stream().filter(entry -> entry.getValue().name().contains(text))
                .map(Map.Entry::getKey).findFirst()).orElse(null));
    }

    private static void click(Bot bot, String text) {
        bot.clickSlot(slot(bot, text));
    }

    private static void shows(Bot bot, String text) {
        slot(bot, text);
    }

    private static String lore(Bot bot, String button) {
        slot(bot, button);
        return String.join(" | ", bot.window().orElseThrow().top().get(slot(bot, button)).lore());
    }

    @Test
    @DisplayName("an hour, ten minutes, a minute and a tick either way; Back changes nothing, Accept keeps it")
    void tickByTick() {
        try (Server server = Server.start("claim-time", List.of("claims-standalone:RainsExtendedClaims-.*"),
                List.of(), Map.of("plugins/RainsExtendedClaims/feature-policies.yml", "features:\n  claim_time: available\n"),
                List.of("Claims are up"))) {
            Await.ticks(60);
            assertThat(server.console("settings set limits.min-area-blocks 1")).contains("is now");
            Bot bo = server.player("Bo");
            Await.ticks(20);

            bo.run("claim new");
            expect(bo, "the selection starts", "Marking out");
            bo.hold(bo.hotbarSlotOf(item -> item.material().toLowerCase().contains("stick")
                    || item.material().toLowerCase().contains("hoe")));
            var at = bo.position();
            int x = (int) Math.floor(at.getX());
            int y = (int) Math.floor(at.getY()) - 1;
            int z = (int) Math.floor(at.getZ());
            bo.useOn(x - 2, y, z - 2);
            expect(bo, "the first corner", "Corner");
            bo.useOn(x + 2, y, z + 2);
            expect(bo, "a name is asked for", "What should it be called?");
            bo.say("home");
            expect(bo, "the claim is made", "Claimed");

            bo.run("claim");
            click(bo, "home");
            click(bo, "Configuration");
            click(bo, "Perks");
            assertThat(lore(bo, "Its own time of day")).contains("follows the world");

            // ---- to the tick: 06:00 is tick 0, then an hour, ten minutes, a minute, a tick forward and one back
            click(bo, "Its own time of day");
            bo.awaitWindow("Time of day");
            click(bo, "Least");
            shows(bo, "06:00 (0 ticks)");
            click(bo, "+1 hour");
            shows(bo, "07:00 (1000 ticks)");
            Await.until("Bo, standing in the claim, sees it while choosing", WAIT,
                    () -> bo.timesOfDay().contains(1000L));
            click(bo, "+10 min");
            click(bo, "+1 min");
            shows(bo, "1184 ticks");
            click(bo, "+1 tick");
            shows(bo, "1185 ticks");
            click(bo, "-1 tick");
            shows(bo, "07:11 (1184 ticks)");
            try {
                // 26.3 sends more than one clock; the day clock is the one that went 1185 and back to 1184.
                Await.until("a single tick reaches the client too", WAIT, () -> {
                    List<Long> seen = bo.timesOfDay();
                    int forward = seen.indexOf(1185L);
                    return forward >= 0 && seen.subList(forward, seen.size()).contains(1184L);
                });
            } catch (AssertionError failed) {
                throw new AssertionError(failed.getMessage() + " — the client saw " + bo.timesOfDay(), failed);
            }
            click(bo, "-1 hour");
            click(bo, "-1 hour");
            shows(bo, "05:11 (23184 ticks)");

            // ---- Back is never mind: the claim still follows the world
            click(bo, "Back");
            bo.awaitWindow("Perks");
            assertThat(lore(bo, "Its own time of day")).contains("follows the world");

            // ---- Accept keeps it
            click(bo, "Its own time of day");
            bo.awaitWindow("Time of day");
            click(bo, "Least");
            click(bo, "+1 hour");
            click(bo, "+1 tick");
            click(bo, "Accept");
            bo.awaitWindow("Perks");
            Await.until("the perk shows the time picked", WAIT, () -> lore(bo, "Its own time of day").contains("07:00"));
            assertThat(lore(bo, "Its own time of day")).contains("running");

            // ---- and opens on it next time
            click(bo, "Its own time of day");
            bo.awaitWindow("Time of day");
            shows(bo, "1001 ticks");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsExtendedClaims")).isEmpty();
        }
    }
}
