package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bait ores on a real server. One bot behaves like an x-ray client: it reads the ores the server
 * shows only to it — fake ones sealed in rock — and tunnels straight for them. Another digs a plain
 * straight tunnel through the same kind of rock. Only the first is reported.
 */
@Tag("e2e")
class XrayScenarioTest {

    private static final int FLOOR = -30;
    private static final String PICKAXE = "minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":10}]";

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** Solid stone in 16x16 columns, the fill command's own size limit. */
    private static void rock(Server server, int fromX, int fromZ, int toX, int toZ) {
        for (int x = fromX; x < toX; x += 16) {
            for (int z = fromZ; z < toZ; z += 16) {
                server.console("fill " + x + " " + (FLOOR - 25) + " " + z + " " + (x + 15) + " " + (FLOOR + 25) + " "
                        + (z + 15) + " minecraft:stone");
            }
        }
    }

    /** One step of a two-high tunnel: dig the next feet and head block, then stand there. */
    private static int[] step(Bot bot, int[] at, int[] next) {
        bot.dig(next[0], next[1], next[2]).dig(next[0], next[1] + 1, next[2]);
        if (next[1] < at[1]) {
            bot.dig(at[0], at[1] + 1, at[2]);
        }
        sleep(60);
        bot.moveTo(next[0] + 0.5, next[1], next[2] + 0.5, true).tickEnd();
        sleep(40);
        // A refused move teleports the bot back; carry on from wherever the server says it is.
        var actual = bot.position();
        return new int[]{(int) Math.floor(actual.getX()), (int) Math.floor(actual.getY()), (int) Math.floor(actual.getZ())};
    }

    /** What an x-ray client sees: blocks the server sent this client alone, still not taken back. */
    private static Map<String, int[]> baitSeen(Bot bot) {
        Map<String, int[]> live = new HashMap<>();
        for (int[] update : bot.blockUpdates()) {
            String key = update[0] + "," + update[1] + "," + update[2];
            if (update[3] == 0) {
                live.remove(key);
            } else if (live.containsKey(key)) {
                live.remove(key);   // a second update for the same spot is the real block coming back
            } else if (update[1] < FLOOR + 20) {
                live.put(key, update);
            }
        }
        return live;
    }

    @Test
    @DisplayName("tunnelling straight to bait ores files an x-ray report; an honest tunnel does not")
    void baitCatchesXray() {
        try (Server server = Server.start("xray",
                List.of("moderation-standalone:RainsModeration-.*"), List.of("Moderation is up"))) {
            server.console("forceload add -16 -16 160 112");
            rock(server, 0, 0, 144, 96);
            server.console("fill 20 " + FLOOR + " 20 20 " + (FLOOR + 1) + " 20 minecraft:air");
            server.console("fill 96 " + FLOOR + " 10 96 " + (FLOOR + 1) + " 10 minecraft:air");

            Bot ada = server.admin("Ada");
            Bot xray = server.player("Xray");
            Bot honest = server.player("Honest");
            for (String name : List.of("Xray", "Honest")) {
                server.console("gamemode survival " + name);
                server.console("item replace entity " + name + " weapon.mainhand with " + PICKAXE);
                server.console("effect give " + name + " minecraft:night_vision infinite 0 true");
            }
            server.console("tp Xray 20.5 " + FLOOR + " 20.5");
            server.console("tp Honest 96.5 " + FLOOR + " 10.5");
            ada.forgetChat();

            Await.until("the x-ray bot is shown bait ores", Duration.ofSeconds(30), () -> baitSeen(xray).size() >= 8);

            // The honest bot: a straight two-high tunnel, eastward, blind to everything.
            int[] here = {96, FLOOR, 10};
            for (int t = 0; t < 110; t++) {
                here = step(honest, here, new int[]{here[0] + (t < 35 ? 1 : 0), FLOOR, here[2] + (t < 35 ? 0 : 1)});
            }

            // The x-ray bot: always toward the nearest bait it can see, until enough are reached.
            int[] at = {20, FLOOR, 20};
            int reached = 0;
            java.util.Set<String> givenUp = new java.util.HashSet<>();
            for (int round = 0; round < 60 && reached < 10; round++) {
                Map<String, int[]> seen = baitSeen(xray);
                seen.keySet().removeAll(givenUp);
                if (seen.isEmpty()) {
                    sleep(1000);
                    continue;
                }
                int[] target = null;
                double best = Double.MAX_VALUE;
                for (int[] bait : seen.values()) {
                    double d = Math.pow(bait[0] - at[0], 2) + Math.pow(bait[1] - at[1], 2) + Math.pow(bait[2] - at[2], 2);
                    if (d < best) {
                        best = d;
                        target = bait;
                    }
                }
                String key = target[0] + "," + target[1] + "," + target[2];
                for (int guard = 0; baitSeen(xray).containsKey(key) && guard < 60; guard++) {
                    int[] next = at.clone();
                    if (at[1] > target[1]) {
                        next[1]--;
                    } else if (at[1] + 1 < target[1]) {
                        next[1]++;
                    } else if (at[0] != target[0]) {
                        next[0] += Integer.signum(target[0] - at[0]);
                    } else if (at[2] != target[2]) {
                        next[2] += Integer.signum(target[2] - at[2]);
                    } else {
                        break;
                    }
                    at = step(xray, at, next);
                }
                if (baitSeen(xray).containsKey(key)) {
                    givenUp.add(key);
                } else {
                    reached++;
                }
            }
            assertThat(reached).as("the x-ray bot reached its baits").isGreaterThanOrEqualTo(8);

            Await.until("staff hear about the x-ray", Duration.ofSeconds(20), () ->
                    ada.chatText().stream().anyMatch(line -> line.contains("Xray") && line.contains("x-ray")));
            assertThat(ada.chatText()).noneMatch(line -> line.contains("Honest") && line.contains("x-ray"));
            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
