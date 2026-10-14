package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The shard world's tyrants on a real server: a zombie tyrant hit below half its health calls four zombies, once; and a
 * tyrant's damage is its kind's multiple of what it would deal.
 */
@Tag("e2e")
class ShardTyrantsScenarioTest {

    private static final String SHARD = "execute in minecraft:shardlands run ";
    private static final String TYRANT = "BukkitValues:{\"rainsenchants:shard_tier\":\"tyrant\"}";

    private static int count(Server server, String selector) {
        Matcher found = Pattern.compile("Count: (\\d+)").matcher(server.console(SHARD + "execute if entity " + selector));
        return found.find() ? Integer.parseInt(found.group(1)) : 0;
    }

    private static double health(Server server, String who) {
        Matcher found = Pattern.compile("data: ([0-9.]+)f").matcher(server.console(SHARD + "data get entity " + who + " Health"));
        return found.find() ? Double.parseDouble(found.group(1)) : -1;
    }

    @Test
    @DisplayName("a zombie tyrant calls for help below half its health, once; a tyrant hits 2.2 times as hard")
    void tyrants() {
        try (Server server = Server.start("shard-tyrants", List.of("enchants-standalone:RainsEnchants-.*"),
                List.of("Enchants are up"))) {
            server.console(SHARD + "forceload add -16 -16 16 16");
            server.console(SHARD + "fill -8 300 -8 8 300 8 minecraft:stone");
            server.console(SHARD + "fill -8 301 -8 8 306 8 minecraft:air");
            server.player("Fighter");
            // Creative while the tyrant calls for help: the four it calls are after whoever hit it.
            server.console("gamemode creative Fighter");
            server.console(SHARD + "tp Fighter 0.5 301 6.5");

            server.console(SHARD + "summon minecraft:zombie 0.5 301 0.5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"boss\"]," + TYRANT + "}");
            Await.ticks(10);
            server.console(SHARD + "damage @e[type=zombie,tag=boss,limit=1] 12 minecraft:player_attack by Fighter");
            Await.ticks(10);
            assertThat(count(server, "@e[type=zombie]")).as("the tyrant and four called").isEqualTo(5);
            server.console(SHARD + "damage @e[type=zombie,tag=boss,limit=1] 2 minecraft:player_attack by Fighter");
            Await.ticks(10);
            assertThat(count(server, "@e[type=zombie]")).as("no second call").isEqualTo(5);
            server.console(SHARD + "kill @e[type=zombie]");
            server.console("gamemode survival Fighter");
            server.console("effect give Fighter minecraft:instant_health 1 10");
            Await.ticks(10);

            server.console(SHARD + "summon minecraft:husk 0.5 301 3.5 {NoAI:1b,PersistenceRequired:1b,Tags:[\"hitter\"]," + TYRANT + "}");
            Await.ticks(10);
            server.console(SHARD + "damage Fighter 2 minecraft:mob_attack by @e[type=husk,tag=hitter,limit=1]");
            Await.ticks(5);
            // Times 1.5 on top: the shard world is on hard, where the game itself makes mobs hit harder.
            assertThat(health(server, "Fighter")).as("2 damage times 1.3 cubed, on hard")
                    .isCloseTo(20 - 2 * Math.pow(1.3, 3) * 1.5, within(0.05));
        }
    }
}
