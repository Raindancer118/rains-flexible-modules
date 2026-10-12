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

/** {@code /hub} and {@code /smp}: a door per installed module, hidden for those whose commands a player cannot use. */
@Tag("e2e")
class HubScenarioTest {

    static final List<String> PLUGINS = List.of("claims-standalone:.*", "homes-standalone:.*",
            "economy-standalone:.*", "essentials-standalone:.*", "moderation-standalone:.*",
            "warp-standalone:.*", "tpa-standalone:.*", "jobs-standalone:.*",
            "rtp-standalone:.*", "roles-standalone:.*", "cosmetics-standalone:.*");

    private static int slotNamed(Bot bot, String text) {
        return Await.value("a button named " + text, Duration.ofSeconds(15), () -> bot.window().flatMap(window ->
                window.top().entrySet().stream().filter(entry -> entry.getValue().name().contains(text))
                        .map(Map.Entry::getKey).findFirst()).orElse(null));
    }

    private static List<String> names(Bot bot) {
        return bot.window().map(window -> window.top().values().stream().map(item -> item.name()).toList())
                .orElse(List.of());
    }

    private static boolean hasButton(Bot bot, String text) {
        return bot.window().map(window -> window.top().values().stream()
                .anyMatch(item -> item.name().contains(text))).orElse(false);
    }

    @Test
    @DisplayName("hub opens through both names, shows doors by what the viewer may use, and a door lists its commands")
    void hub() {
        try (Server server = Server.start("hub", PLUGINS, List.of())) {
            Await.ticks(60);
            Bot bo = server.player("Bo");
            Bot ada = server.admin("Ada");

            bo.run("hub");
            bo.awaitWindow("Hub");
                        assertThat(hasButton(bo, "Warps")).isTrue();
            assertThat(hasButton(bo, "Homes")).as("every /home command needs a permission Bo lacks").isFalse();
            bo.clickSlot(slotNamed(bo, "Warps"));
            bo.awaitWindow("Warps");
            assertThat(hasButton(bo, "/warp")).isTrue();
            bo.closeWindow();

            bo.run("smp");
            bo.awaitWindow("Hub");

            ada.run("hub");
            ada.awaitWindow("Hub");
            assertThat(hasButton(ada, "Homes")).isTrue();
            assertThat(hasButton(ada, "Moderation")).isTrue();

            ada.run("hub homes");
            ada.awaitWindow("Homes");

            String console = server.console("hub");
            assertThat(console).contains("feature(s)");
            assertThat(server.paper.errorsFrom("RainsCore")).isEmpty();
        }
    }
}
