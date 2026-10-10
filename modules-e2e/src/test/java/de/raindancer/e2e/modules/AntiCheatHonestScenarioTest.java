package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What honest players do all day on a survival server, each with its own bot: walking over paths,
 * slabs, chests, snow, carpet, leaf litter and grass, digging straight down, sprinting, riding a boat,
 * clicking fast. Lilly's SMP flagged nearly every player for NoFall, Fly, Speed, Sprint, NoSwing and
 * BadPackets; none of it may flag. Sprinting sideways and never swinging still must.
 */
@Tag("e2e")
class AntiCheatHonestScenarioTest {

    private static final int FLOOR = 100;
    private static final int FROM = -10;
    private static final int TO = 10;

    /** A strip of one block over the stone floor, and how high a player standing on it is. */
    private record Strip(String name, String block, double height) {
    }

    private static final List<Strip> STRIPS = List.of(
            new Strip("Path", "minecraft:dirt_path", 0.9375),
            new Strip("Slab", "minecraft:oak_slab", 0.5),
            new Strip("Chest", "minecraft:chest", 0.875),
            new Strip("SoulSand", "minecraft:soul_sand", 0.875),
            new Strip("Snow", "minecraft:snow[layers=3]", 0.25),
            new Strip("Carpet", "minecraft:white_carpet", 0.0625),
            new Strip("Litter", "minecraft:leaf_litter[segment_amount=4]", 0),
            new Strip("Grass", "minecraft:short_grass", 0),
            new Strip("Stairs", "minecraft:oak_stairs[half=top]", 1.0),
            new Strip("Fence", "minecraft:oak_fence", 1.5));

    private static void tick(Bot bot, Runnable sends) {
        sends.run();
        bot.tickEnd();
        sleep(50);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** Vanilla walking along x from one end of the strip to the other, at the strip's height. */
    private static void walk(Bot bot, double y, double z) {
        double x = FROM + 0.5;
        double v = 0;
        while (x < TO - 0.5) {
            v = v * 0.546 + 0.098;
            double at = x += v;
            tick(bot, () -> bot.moveTo(at, y, z, true));
        }
        for (int t = 0; t < 10; t++) {
            v *= 0.546;
            double at = x += v;
            tick(bot, () -> bot.moveTo(at, y, z, true));
        }
    }

    @Test
    @DisplayName("walking on paths, slabs, chests, snow, carpet, leaf litter and grass, digging down and sprinting are clean; sprinting sideways is not")
    void honestGround() {
        try (Server server = Server.start("anticheat-honest",
                List.of("anticheat-standalone:RainsAntiCheat-.*"), List.of("Anti-cheat is up"))) {
            server.console("forceload add -16 -32 16 32");
            server.console("fill -12 " + (FLOOR - 15) + " -30 12 " + FLOOR + " 30 minecraft:stone");
            server.console("fill -12 " + (FLOOR + 1) + " -30 12 " + (FLOOR + 6) + " 30 minecraft:air");
            server.console("gamerule advance_time false");

            Map<String, Bot> bots = new LinkedHashMap<>();
            Map<String, Double> zOf = new LinkedHashMap<>();
            for (int i = 0; i < STRIPS.size(); i++) {
                Strip strip = STRIPS.get(i);
                int z = -28 + i * 4;
                // Chests and hoppers placed with fill all face the same way and do not join up.
                server.console("fill " + FROM + " " + (FLOOR + 1) + " " + z + " " + TO + " " + (FLOOR + 1) + " " + z + " " + strip.block());
                bots.put(strip.name(), server.player(strip.name()));
                zOf.put(strip.name(), z + 0.5);
            }
            // A path through a lawn: up and down a sixteenth every two blocks, as on every SMP's roads.
            int mixedZ = -28 + STRIPS.size() * 4;
            server.console("fill " + FROM + " " + (FLOOR + 1) + " " + mixedZ + " " + TO + " " + (FLOOR + 1) + " " + mixedZ + " minecraft:grass_block");
            for (int x = FROM; x <= TO; x += 4) {
                server.console("fill " + x + " " + (FLOOR + 1) + " " + mixedZ + " " + (x + 1) + " " + (FLOOR + 1) + " " + mixedZ + " minecraft:dirt_path");
            }
            Bot mixed = server.player("Mixed");
            // A shaft of instant-break blocks to dig straight down through, the way players dig for ore.
            server.console("fill 0 " + (FLOOR - DEPTH + 1) + " " + DIG_Z + " 0 " + FLOOR + " " + DIG_Z + " minecraft:tnt");
            Bot digger = server.player("Digger");
            Bot forward = server.player("Forward");
            Bot sideways = server.player("Sideways");
            Bot rider = server.player("Rider");
            Bot clicker = server.player("Clicker");
            Bot unswung = server.player("Unswung");
            Bot dummy = server.player("Dummy");
            Bot dummy2 = server.player("Dummytwo");

            for (Strip strip : STRIPS) {
                double y = FLOOR + 1 + strip.height();
                server.console("tp " + strip.name() + " " + (FROM + 0.5) + " " + y + " " + zOf.get(strip.name()) + " -90 0");
            }
            server.console("tp Mixed " + (FROM + 0.5) + " " + (FLOOR + 2) + " " + (mixedZ + 0.5) + " -90 0");
            server.console("tp Digger 0.5 " + (FLOOR + 1) + " " + (DIG_Z + 0.5) + " 0 90");
            server.console("tp Forward " + (FROM + 0.5) + " " + (FLOOR + 1) + " 16.5 -90 0");
            server.console("tp Sideways " + (FROM + 0.5) + " " + (FLOOR + 1) + " 20.5 0 0");
            server.console("summon minecraft:oak_boat 8.5 " + (FLOOR + 1) + " 18.5 {Tags:[\"ride\"]}");
            server.console("tp Rider 8.5 " + (FLOOR + 1) + " 18.5");
            server.console("tp Clicker -10.5 " + (FLOOR + 1) + " 23.5 -90 0");
            server.console("tp Dummy -8.5 " + (FLOOR + 1) + " 23.5 90 0");
            server.console("tp Unswung -10.5 " + (FLOOR + 1) + " 28.5 -90 0");
            server.console("tp Dummytwo -8.5 " + (FLOOR + 1) + " 28.5 90 0");
            Await.ticks(80);
            server.console("ride Rider mount @e[type=minecraft:oak_boat,tag=ride,limit=1]");
            forward.keys(true, false, false, false, false, false, true).sprinting(true);
            // Facing south, walking east: only the left key is down — the omni-sprint a cheat gives.
            sideways.keys(false, false, true, false, false, false, true).sprinting(true);
            Await.ticks(20);
            // Lilly's SMP runs at 17-19 tps: the server judges fewer ticks than the clients send. Not 18: on a
            // busy test machine that dips under min-tps 17, and the movement checks rightly pause.
            server.console("tick rate 19");

            List<Thread> walkers = new ArrayList<>();
            for (Strip strip : STRIPS) {
                Bot bot = bots.get(strip.name());
                double y = FLOOR + 1 + strip.height();
                double z = zOf.get(strip.name());
                walkers.add(Thread.ofVirtual().start(() -> walk(bot, y, z)));
            }
            walkers.add(Thread.ofVirtual().start(() -> {
                double x = FROM + 0.5;
                double v = 0;
                while (x < TO - 0.5) {
                    v = v * 0.546 + 0.098;
                    double at = x += v;
                    // The box is 0.6 wide: on grass while any part of it is over grass.
                    boolean overGrass = onGrass(at - 0.3) || onGrass(at + 0.3);
                    double y = FLOOR + 1 + (overGrass ? 1.0 : 0.9375);
                    tick(mixed, () -> mixed.moveTo(at, y, mixedZ + 0.5, true));
                }
            }));
            walkers.add(Thread.ofVirtual().start(() -> digDown(digger)));
            walkers.add(Thread.ofVirtual().start(() -> sprint(forward, 16.5)));
            walkers.add(Thread.ofVirtual().start(() -> sprint(sideways, 20.5)));
            // A passenger's client sends its rotation every tick, whether it changed or not.
            walkers.add(Thread.ofVirtual().start(() -> {
                for (int t = 0; t < 40; t++) {
                    tick(rider, () -> rider.look(30, 10));
                }
            }));
            // Clicking fast: one swing, then hits that land while the arm is still swinging. A vanilla
            // client attacks first and moves afterwards, within the same tick.
            walkers.add(Thread.ofVirtual().start(() -> {
                // The bot answered the teleport with a move of its own; a clean tick first.
                tick(clicker, () -> clicker.moveTo(-10.5, FLOOR + 1, 23.5, true));
                for (int round = 0; round < 6; round++) {
                    tick(clicker, () -> clicker.attack(dummy.id()).swing().moveTo(-10.5, FLOOR + 1, 23.5, true));
                    for (int t = 0; t < 3; t++) {
                        tick(clicker, () -> clicker.attack(dummy.id()).moveTo(-10.5, FLOOR + 1, 23.5, true));
                    }
                    for (int t = 0; t < 8; t++) {
                        tick(clicker, () -> clicker.moveTo(-10.5, FLOOR + 1, 23.5, true));
                    }
                }
            }));
            walkers.add(Thread.ofVirtual().start(() -> {
                for (int round = 0; round < 6; round++) {
                    tick(unswung, () -> unswung.attack(dummy2.id()).moveTo(-10.5, FLOOR + 1, 28.5, true));
                    for (int t = 0; t < 10; t++) {
                        tick(unswung, () -> unswung.moveTo(-10.5, FLOOR + 1, 28.5, true));
                    }
                }
            }));
            for (Thread walker : walkers) {
                try {
                    walker.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            Await.ticks(20);

            assertThat(server.console("execute if block 0 " + (FLOOR - DEPTH + 1) + " " + DIG_Z + " minecraft:air"))
                    .as("the digger really dug the whole shaft").contains("passed");
            assertThat(digger.position().getY()).isEqualTo(FLOOR - DEPTH + 1);

            Map<String, String> records = new LinkedHashMap<>();
            for (String name : bots.keySet()) {
                records.put(name, server.console("anticheat info " + name));
            }
            records.put("Mixed", server.console("anticheat info Mixed"));
            records.put("Digger", server.console("anticheat info Digger"));
            records.put("Forward", server.console("anticheat info Forward"));
            records.put("Rider", server.console("anticheat info Rider"));
            records.put("Clicker", server.console("anticheat info Clicker"));
            assertThat(server.console("anticheat info Unswung")).as("hitting without ever swinging").contains("NoSwing");
            assertThat(server.console("anticheat info Sideways")).as("sprinting sideways is held to walking speed")
                    .contains("Speed");
            Map<String, String> flagged = new LinkedHashMap<>();
            records.forEach((name, record) -> {
                if (!record.contains("Squeaky clean")) {
                    flagged.put(name, record + "\n" + server.console("anticheat log " + name));
                }
            });
            assertThat(flagged).as("honest walkers that were flagged").isEmpty();
            assertThat(server.paper.errorsFrom("RainsCore", "RainsAntiCheat")).isEmpty();
        }
    }

    private static final int DEPTH = 12;
    private static final int DIG_Z = 26;

    /**
     * Breaks the block underfoot and falls onto the next, {@link #DEPTH} times. The last move on the
     * ground, the break and the first move falling reach the server together, as they do whenever the
     * network bunches a client's ticks: the move made on the block is judged after the block is gone.
     */
    private static void digDown(Bot bot) {
        double y = FLOOR + 1;
        for (int t = 0; t < 5; t++) {
            double at = y;
            tick(bot, () -> bot.moveTo(0.5, at, DIG_Z + 0.5, true));
        }
        for (int level = 0; level < DEPTH; level++) {
            double top = y;
            bot.moveTo(0.5, top, DIG_Z + 0.5, true).tickEnd();
            bot.dig(0, (int) top - 1, DIG_Z);
            double v = (0 - 0.08) * 0.98;
            double at = top + v;
            bot.moveTo(0.5, at, DIG_Z + 0.5, false).tickEnd();
            sleep(100);
            double fell = at;
            while (true) {
                v = (v - 0.08) * 0.98;
                fell += v;
                boolean landed = fell <= top - 1;
                double now = landed ? top - 1 : fell;
                tick(bot, () -> bot.moveTo(0.5, now, DIG_Z + 0.5, landed));
                if (landed) {
                    break;
                }
            }
            y = top - 1;
            for (int t = 0; t < 4; t++) {
                double still = y;
                tick(bot, () -> bot.moveTo(0.5, still, DIG_Z + 0.5, true));
            }
        }
    }

    /** Sprinting from a standstill to full speed along x, the way vanilla accelerates. */
    private static void sprint(Bot bot, double z) {
        double x = FROM + 0.5;
        double v = 0;
        while (x < TO - 0.5) {
            v = v * 0.546 + 0.13;
            double at = x += v;
            tick(bot, () -> bot.moveTo(at, FLOOR + 1, z, true));
        }
    }

    private static boolean onGrass(double x) {
        int block = (int) Math.floor(x);
        return Math.floorMod(block - FROM, 4) >= 2;
    }
}
