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

/**
 * Heat-seeking on a real server: a zombie stands twenty blocks ahead and six to the side. A plain arrow shot straight
 * ahead flies past it; the same shot from a heat-seeking bow locks on and hits.
 */
@Tag("e2e")
class HeatSeekingScenarioTest {

    private static final int Y = 64;

    private static double health(Server server, String tag) {
        Matcher found = Pattern.compile("data: ([0-9.]+)f").matcher(
                server.console("data get entity @e[type=husk,tag=" + tag + ",limit=1] Health"));
        return found.find() ? Double.parseDouble(found.group(1)) : -1;
    }

    private static void shoot(Server server, Bot archer, String bow, String target) {
        server.console("clear Archer");
        server.console("give Archer " + bow);
        server.console("give Archer arrow 8");
        server.console("summon minecraft:husk 6.5 " + Y + " -19.5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"" + target + "\"]}");
        server.console("tp Archer 0.5 " + Y + " 0.5 180 0");
        Await.ticks(20);
        archer.hold(archer.hotbarSlotOf(item -> item.material().toLowerCase().contains("bow")));
        archer.look(180, 0).useHeld();
        Await.ticks(25);
        archer.look(180, 0).releaseUse();
        Await.ticks(60);
    }

    @Test
    @DisplayName("a plain arrow aimed past the husk misses, so does a precise heat-seeking one; a wide one locks on and hits it")
    void missileLock() {
        try (Server server = Server.start("heat-seeking", List.of("enchants-standalone:RainsEnchants-.*"),
                List.of("Enchants are up"))) {
            // Night, and husks: a zombie in the sun burns, and a burn looks like a hit.
            server.console("time set midnight");
            server.console("gamerule doDaylightCycle false");
            server.console("forceload add -16 -32 16 16");
            server.console("fill -8 " + (Y - 1) + " -32 16 " + (Y - 1) + " 8 minecraft:stone");
            server.console("fill -8 " + Y + " -32 16 " + (Y + 6) + " 8 minecraft:air");
            Bot archer = server.player("Archer");
            server.console("gamemode survival Archer");

            shoot(server, archer, "bow", "plain");
            assertThat(health(server, "plain")).as("the plain arrow flew past").isEqualTo(20.0);
            server.console("kill @e[type=husk]");
            server.console("kill @e[type=arrow]");

            // Precise, as everybody starts: it takes what the crosshair is on, and the husk is well off it.
            shoot(server, archer, "bow[enchantments={\"rainsenchants:heat_seeking\":3}]", "precise");
            assertThat(health(server, "precise")).as("precise: nothing near the crosshair, no lock").isEqualTo(20.0);
            server.console("kill @e[type=husk]");
            server.console("kill @e[type=arrow]");

            archer.forgetChat();
            archer.run("heatseeking");
            Await.until("now wide", java.time.Duration.ofSeconds(5),
                    () -> archer.chatText().stream().anyMatch(line -> line.contains("Wide")));
            shoot(server, archer, "bow[enchantments={\"rainsenchants:heat_seeking\":3}]", "locked");
            assertThat(health(server, "locked")).as("wide: the heat-seeking arrow found it and hit").isBetween(0.0, 19.9);
        }
    }
}
