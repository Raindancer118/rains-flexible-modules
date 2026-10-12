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
 *
 * <p>Both dig with a pickaxe that breaks stone at once, and a real client shows the block behind a
 * broken one before the server can answer. So neither may ever dig next to a bait it is still shown —
 * that would flash a fake ore at an honest player.
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

    /** A dig next to a bait the client was still shown; {@code since} is how many block updates it had then. */
    private record Flash(Bot bot, int x, int y, int z, String bait, int state, int since) {
        /**
         * Only a dig the server carried out breaks the block; a refused one (the bot is not where it
         * thinks) is answered with the block put back, and never showed anything.
         */
        boolean happened() {
            List<int[]> updates = bot.blockUpdates();
            if (!exposed(updates)) {
                return false;
            }
            for (int i = since; i < updates.size(); i++) {
                int[] u = updates.get(i);
                if (u[0] == x && u[1] == y && u[2] == z) {
                    return u[3] == 0;
                }
            }
            return false;
        }

        /**
         * A real client can only dig a block it can see: one next to open space. The bot is not held to
         * that — when the server refuses its moves it digs blind into solid rock — and such a dig is
         * not something an honest player can do.
         */
        private boolean exposed(List<int[]> updates) {
            java.util.Set<String> open = new java.util.HashSet<>(START_AIR);
            for (int i = 0; i < since && i < updates.size(); i++) {
                int[] u = updates.get(i);
                if (u[3] == 0) {
                    open.add(u[0] + "," + u[1] + "," + u[2]);
                }
            }
            for (int[] side : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
                if (open.contains((x + side[0]) + "," + (y + side[1]) + "," + (z + side[2]))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return bot.name() + " dug " + x + "," + y + "," + z + " next to bait " + bait + " (state " + state + ")";
        }
    }

    private static final java.util.Set<String> START_AIR = java.util.Set.of(
            "20," + FLOOR + ",20", "20," + (FLOOR + 1) + ",20", "96," + FLOOR + ",10", "96," + (FLOOR + 1) + ",10");

    private static final List<Flash> FLASHES = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Digs a block, noting any bait this client still shows right next to it. */
    private static void dig(Bot bot, int x, int y, int z) {
        Map<String, int[]> seen = baitSeen(bot);
        int since = bot.blockUpdates().size();
        for (int[] side : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) {
            String key = (x + side[0]) + "," + (y + side[1]) + "," + (z + side[2]);
            if (seen.containsKey(key)) {
                FLASHES.add(new Flash(bot, x, y, z, key, seen.get(key)[3], since));
            }
        }
        DUG.add(bot.name() + ":" + x + "," + y + "," + z);
        bot.dig(x, y, z);
    }

    private static final java.util.Set<String> DUG = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** One step of a two-high tunnel: dig the next feet and head block, then stand there. */
    private static int[] step(Bot bot, int[] at, int[] next) {
        dig(bot, next[0], next[1], next[2]);
        dig(bot, next[0], next[1] + 1, next[2]);
        if (next[1] < at[1]) {
            dig(bot, at[0], at[1] + 1, at[2]);
        }
        sleep(60);
        bot.moveTo(next[0] + 0.5, next[1], next[2] + 0.5, true).tickEnd();
        sleep(40);
        // A refused move teleports the bot back; carry on from wherever the server says it is.
        var actual = bot.position();
        return new int[]{(int) Math.floor(actual.getX()), (int) Math.floor(actual.getY()), (int) Math.floor(actual.getZ())};
    }

    /** Inside the stone this test lays down; outside, natural caves send water and lava updates too. */
    private static boolean inRock(int[] at) {
        return at[0] >= 0 && at[0] < 144 && at[2] >= 0 && at[2] < 96;
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
            } else if (update[1] < FLOOR + 20 && inRock(update) && !DUG.contains(bot.name() + ":" + key)) {
                // A spot it dug itself is the server putting a refused dig back, never a bait.
                live.put(key, update);
            }
        }
        return live;
    }

    private static boolean xrayReported(Bot staff) {
        return staff.chatText().stream().anyMatch(line -> line.contains("Xray") && line.contains("x-ray"));
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
            // Until staff hear of it, not a fixed number of baits: on a loaded machine some moves are refused,
            // so the same number of baits can carry less evidence. The honest bot is still checked below.
            for (int round = 0; round < 60 && (reached < 10 || !xrayReported(ada)); round++) {
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
            sleep(500);
            assertThat(FLASHES.stream().filter(Flash::happened).toList()).as("a bait shown next to a block being dug").isEmpty();

            Await.until("staff hear about the x-ray", Duration.ofSeconds(20), () ->
                    ada.chatText().stream().anyMatch(line -> line.contains("Xray") && line.contains("x-ray")));
            assertThat(ada.chatText()).noneMatch(line -> line.contains("Honest") && line.contains("x-ray"));
            assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration")).isEmpty();
        }
    }
}
