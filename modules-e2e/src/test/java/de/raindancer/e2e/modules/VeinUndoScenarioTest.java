package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.Downloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /vein undo} against the real Veinminer: a bot mines a vein of diamond ore, undoes it, and the
 * ore is back while every diamond it dropped is gone — from the ground and from the inventory — and a
 * diamond somebody else dropped in the tunnel is still there. Mined again, the vein gives the same
 * diamonds and no experience; with the diamonds thrown away, undo refuses.
 */
@Tag("e2e")
class VeinUndoScenarioTest {

    private static final String VEINMINER_URL =
            "https://cdn.modrinth.com/data/OhduvhIc/versions/O12m3jOR/veinminer-paper-2.12.3.jar";
    private static final String VEINMINER_SHA1 = "d40cdbd279781b0045b65156abd8079b77484634";

    private static final int Y = 64;
    private static final int FIRST = 3;
    private static final int LAST = 7;
    private static final int VEIN = LAST - FIRST + 1;
    /** No Fortune, so every ore drops exactly one diamond and the counts below are exact. */
    private static final String PICKAXE =
            "minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":10}]";

    private static int diamonds(Bot bot) {
        return bot.items().stream().filter(item -> item.is("diamond")).mapToInt(Bot.Item::amount).sum();
    }

    private static boolean all(Server server, String block) {
        for (int x = FIRST; x <= LAST; x++) {
            if (!server.console("execute if block " + x + " " + Y + " 5 " + block).contains("passed")) {
                return false;
            }
        }
        return true;
    }

    private static boolean anyLying(Server server, String type) {
        return server.console("execute if entity @e[type=" + type + "]").contains("passed");
    }

    private static int query(Server server, String what) {
        String answer = server.console("xp query Miner " + what);
        return Integer.parseInt(answer.replaceAll("\\D+", " ").trim().split(" ")[0]);
    }

    /** Whether Miner has any experience at all — points alone are only the part into the current level. */
    private static boolean experienced(Server server) {
        return query(server, "levels") > 0 || query(server, "points") > 0;
    }

    @Test
    @DisplayName("a mined vein comes back for exactly its diamonds, mines again for the same, and refuses once they are gone")
    void undo() {
        Path veinminer = Downloads.pinned("veinminer-paper-2.12.3.jar", VEINMINER_URL, VEINMINER_SHA1);
        try (Server server = Server.start("veinundo", List.of("veintoggle-standalone:RainsVeinToggle-.*"),
                List.of(veinminer), List.of("Vein toggle is up: Veinminer is on"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -4 " + (Y - 4) + " -4 12 " + (Y + 4) + " 12 minecraft:stone");
            server.console("fill 0 " + Y + " 5 2 " + (Y + 1) + " 5 minecraft:air");
            server.console("fill " + FIRST + " " + Y + " 5 " + LAST + " " + Y + " 5 minecraft:diamond_ore");

            Bot miner = server.player("Miner");
            server.console("gamemode survival Miner");
            server.console("clear Miner");
            server.console("item replace entity Miner weapon.mainhand with " + PICKAXE);
            server.console("tp Miner 2.5 " + Y + " 5.5 -90 0");
            Await.ticks(20);

            miner.dig(FIRST, Y, 5);
            Await.until("the whole vein comes down", Duration.ofSeconds(15), () -> all(server, "minecraft:air"));
            Await.ticks(40);
            assertThat(experienced(server) || anyLying(server, "experience_orb"))
                    .as("the first mining gives experience, as Veinminer always does").isTrue();

            // Somebody else's diamond, lying in the tunnel out of the miner's reach: never part of the price.
            server.console("summon item 0.5 " + Y + " 5.5 {Item:{id:\"minecraft:diamond\",count:1},PickupDelay:32767}");
            miner.forgetChat();
            miner.run("ctrl-z");
            miner.expectChat("restored.");
            Await.until("the ore is back", Duration.ofSeconds(10), () -> all(server, "minecraft:diamond_ore"));
            Await.until("every diamond of the vein is taken back", Duration.ofSeconds(10),
                    () -> diamonds(miner) == 0
                            && server.console("execute if entity @e[type=item]").contains("Count: 1")
                            && server.console("execute if entity @e[type=item,x=0.5,y=" + Y + ",z=5.5,distance=..1]")
                                    .contains("passed"));
            server.console("kill @e[type=item]");

            miner.forgetChat();
            miner.run("ctrl-z");
            miner.expectChat("No vein of yours to undo");

            // Mined again: the same diamonds, and no experience for ore that was already paid for once.
            server.console("kill @e[type=experience_orb]");
            server.console("xp set Miner 0 levels");
            server.console("xp set Miner 0 points");
            server.console("tp Miner 2.5 " + Y + " 5.5 -90 0");
            miner.dig(FIRST, Y, 5);
            Await.until("the vein comes down again", Duration.ofSeconds(15), () -> all(server, "minecraft:air"));
            // Along the tunnel and back, slowly enough for every drop's pickup delay to run out.
            for (int x : new int[]{3, 5, 7, 5, 3}) {
                server.console("tp Miner " + x + ".5 " + Y + " 5.5");
                Await.ticks(20);
            }
            Await.until("all " + VEIN + " diamonds are picked up", Duration.ofSeconds(15),
                    () -> diamonds(miner) == VEIN);
            Await.ticks(20);
            assertThat(experienced(server)).as("no experience the second time").isFalse();
            assertThat(anyLying(server, "experience_orb")).isFalse();

            // Thrown away, the diamonds cannot pay for the vein, so it stays mined.
            server.console("clear Miner minecraft:diamond");
            server.console("tp Miner 2.5 " + Y + " 5.5");
            Await.ticks(40);
            miner.forgetChat();
            miner.run("ctrl-z");
            miner.expectChat("None of that vein could go back");
            miner.expectChat("what they dropped is gone");
            assertThat(all(server, "minecraft:air")).isTrue();

            assertThat(server.paper.errorsFrom("RainsCore", "RainsVeinToggle")).isEmpty();
        }
    }

    /** Lays the vein again and has {@code miner}, at the tunnel mouth, mine it. */
    private static void mineVein(Server server, Bot miner) {
        server.console("fill " + FIRST + " " + Y + " 5 " + LAST + " " + Y + " 5 minecraft:diamond_ore");
        server.console("tp " + miner.name() + " 2.5 " + Y + " 5.5 -90 0");
        Await.ticks(10);
        miner.dig(FIRST, Y, 5);
        Await.until("the vein comes down", Duration.ofSeconds(15), () -> all(server, "minecraft:air"));
        // Out of reach of its drops, still near enough to undo it.
        server.console("tp " + miner.name() + " 0.5 " + Y + " 5.5");
    }

    /** {@code collector} walks the tunnel and picks up every diamond in it. */
    private static void collect(Server server, Bot collector, String name) {
        for (int x : new int[]{7, 6, 5, 4, 3, 4, 5, 6, 7}) {
            server.console("tp " + name + " " + x + ".5 " + Y + " 5.5");
            Await.ticks(15);
        }
        Await.until(name + " has picked up the whole vein", Duration.ofSeconds(15),
                () -> diamonds(collector) == VEIN && !anyLying(server, "item"));
        server.console("tp " + name + " 10.5 " + Y + " 5.5");
    }

    @Test
    @DisplayName("/ctrl-z takes the vein back from whoever picked it up: their items, then their money, then the undoer pays what is left")
    void collectorsPay() {
        Path veinminer = Downloads.pinned("veinminer-paper-2.12.3.jar", VEINMINER_URL, VEINMINER_SHA1);
        try (Server server = Server.start("veinundo-collectors",
                List.of("veintoggle-standalone:RainsVeinToggle-.*", "economy-standalone:RainsEconomy-.*"),
                List.of(veinminer), List.of("Vein toggle is up: Veinminer is on", "The economy is up"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -4 " + (Y - 4) + " -4 12 " + (Y + 4) + " 12 minecraft:stone");
            server.console("fill 0 " + Y + " 5 11 " + (Y + 1) + " 5 minecraft:air");
            server.console("fill 0 " + Y + " 5 2 " + (Y + 1) + " 5 minecraft:air");

            Bot miner = server.player("Miner");
            Bot ada = server.player("Ada");
            for (String name : List.of("Miner", "Ada")) {
                server.console("gamemode survival " + name);
                server.console("clear " + name);
            }
            server.console("item replace entity Miner weapon.mainhand with " + PICKAXE);
            server.console("tp Ada 10.5 " + Y + " 5.5");
            server.console("eco set Ada 10000000");
            server.console("eco set Miner 10000000");
            Await.ticks(20);

            // Ada picks the whole vein up and throws two away: three come out of her inventory, two
            // out of her account.
            mineVein(server, miner);
            collect(server, ada, "Ada");
            server.console("clear Ada minecraft:diamond 2");
            Await.ticks(20);
            ada.forgetChat();
            miner.forgetChat();
            miner.run("veinundo");
            miner.expectChat("restored.");
            ada.expectChat("went back out of your inventory");
            ada.expectChat("was taken from your account for 2 item(s)");
            Await.until("the ore is back", Duration.ofSeconds(10), () -> all(server, "minecraft:diamond_ore"));
            Await.until("Ada has none of it left", Duration.ofSeconds(10), () -> diamonds(ada) == 0);

            // Ada has nothing now — no diamonds, no money — so the miner is billed and pays.
            server.console("fill " + FIRST + " " + Y + " 5 " + LAST + " " + Y + " 5 minecraft:air");
            mineVein(server, miner);
            collect(server, ada, "Ada");
            server.console("clear Ada minecraft:diamond");
            assertThat(server.console("eco reset Ada")).as("Ada is broke").doesNotContain("not");
            Await.ticks(20);
            miner.forgetChat();
            miner.run("ctrl-z");
            miner.expectChat("were not given back by anybody");
            assertThat(all(server, "minecraft:air")).as("nothing goes back before the bill is settled").isTrue();
            miner.run("ctrl-z pay");
            miner.expectChat("restored.");
            Await.until("the ore is back again", Duration.ofSeconds(10), () -> all(server, "minecraft:diamond_ore"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsVeinToggle", "RainsEconomy")).isEmpty();
        }
    }
}
