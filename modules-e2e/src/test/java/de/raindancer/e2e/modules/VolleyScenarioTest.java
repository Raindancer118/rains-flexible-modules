package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Volley on a real server: one throw, five tridents in the air — and once they have landed, only the thrown one is left. */
@Tag("e2e")
class VolleyScenarioTest {

    private static final int Y = 64;

    private static int tridents(Server server) {
        Matcher found = Pattern.compile("Count: (\\d+)").matcher(server.console("execute if entity @e[type=trident]"));
        return found.find() ? Integer.parseInt(found.group(1)) : 0;
    }

    @Test
    @DisplayName("Volley II throws four more tridents beside the one thrown, and none of them stays")
    void volley() {
        try (Server server = Server.start("volley", List.of("enchants-standalone:RainsEnchants-.*"),
                List.of("Enchants are up"))) {
            server.console("forceload add -32 -48 32 16");
            server.console("fill -32 " + (Y - 1) + " -48 32 " + (Y - 1) + " 16 minecraft:stone");
            server.console("fill -32 " + Y + " -48 32 " + (Y + 8) + " 16 minecraft:air");
            Bot thrower = server.player("Thrower");
            server.console("gamemode survival Thrower");
            server.console("give Thrower trident[enchantments={\"rainsenchants:volley\":2}]");
            server.console("tp Thrower 0.5 " + Y + " 0.5 180 -10");
            Await.ticks(20);
            thrower.hold(thrower.hotbarSlotOf(item -> item.material().toLowerCase().contains("trident")));
            thrower.look(180, -10).useHeld();
            Await.ticks(15);
            thrower.look(180, -10).releaseUse();
            Await.ticks(4);
            assertThat(tridents(server)).as("five in the air").isEqualTo(5);

            Await.ticks(140);
            assertThat(tridents(server)).as("only the thrown one is left").isEqualTo(1);
        }
    }
}
